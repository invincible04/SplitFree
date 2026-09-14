package com.splitfree.sync.worker

import android.content.Context
import com.splitfree.data.local.dao.EventDao
import com.splitfree.data.local.dao.OutboxDao
import com.splitfree.data.local.entities.OutboxEntity
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.repository.RelaySyncCursors
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.NostrKind
import com.splitfree.domain.crypto.isAddressedTo
import com.splitfree.domain.model.sync.FlushResult
import com.splitfree.domain.model.sync.PullResult
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.SyncEngineContract
import com.splitfree.domain.util.RelayDefaults
import com.splitfree.sync.event.EventProcessor
import com.splitfree.sync.event.ExpenseNotifier
import com.splitfree.sync.event.IngestOutcome
import com.splitfree.sync.event.IngestionContext
import com.splitfree.util.DebugLog as Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pulls events from relays and flushes the local outbox.
 */
@Singleton
class SyncEngine
@Inject
constructor(
    private val eventDao: EventDao,
    private val outboxDao: OutboxDao,
    private val groupRepo: GroupRepositoryContract,
    private val nostrClient: NostrClient,
    private val identity: IdentityContract,
    private val eventProcessor: EventProcessor,
    private val relayCursors: RelaySyncCursors
) : SyncEngineContract {
    /**
     * Pull events for a group from connected relays and process new ones. The group's cursor moves
     * as a progress indicator; correctness comes from each relay's durable coverage cursor.
     *
     * @param groupId target group UUID
     * @param since unix timestamp to fetch events after
     * @param groupKey base64-encoded symmetric group key for decryption
     * @param lenientTimestamp retained for callers; historical reconciliation always permits old events
     */
    override suspend fun pullEvents(
        groupId: String,
        since: Long,
        groupKey: String,
        lenientTimestamp: Boolean
    ): PullResult = pullEvents(groupId, since, groupKey, lenientTimestamp, notifyContext = null)

    /** As the contract overload; with a [notifyContext] incoming expenses raise local notifications. */
    suspend fun pullEvents(
        groupId: String,
        since: Long,
        groupKey: String,
        lenientTimestamp: Boolean = false,
        notifyContext: Context? = null
    ): PullResult {
        // Relays return newest-first. key_rotation must be applied strictly in epoch order and
        // group_meta is last-writer-wins on created_at, so process a catch-up batch oldest-first.
        eventProcessor.retryDeferred(groupId)
        val startedAt = System.currentTimeMillis() / 1000
        val recipient = identity.getPublicKeyHex()
        val cursors = relayCursors.cursors(groupId, recipient)
        val primary = groupRepo.getById(groupId)?.relays.orEmpty().ifEmpty { RelayDefaults.DEFAULT_RELAYS }
        // The configured relays remain relevant even when a health probe or socket is offline.
        // Previously connected relays in the shared pool may also contain the only published copy.
        val targets = (primary + RelayDefaults.FALLBACK_RELAYS + nostrClient.currentRelayUrls())
            .filter { it.startsWith("wss://") }.distinct()
        val windows = targets.associateWith { relay ->
            // Never seed from the legacy group cursor: it may have advanced on fallback-only EOSE.
            val coveredSince = ((cursors[relay] ?: 0L) - CURSOR_OVERLAP_SECS).coerceAtLeast(0)
            minOf(since.coerceAtLeast(0), coveredSince)
        }
        val fetch = nostrClient.fetchEventsByRelay(groupId, windows, recipient)
        // A key replacement during IO changes what can be unwrapped. Never certify the old
        // recipient's window using a processor that now reads another identity's private key.
        if (identity.getPublicKeyHex() != recipient) return PullResult(0, false)
        val events = fetch.events.sortedWith(compareBy<NostrEvent> { it.createdAt }.thenBy { it.id })
        val existingIds = eventDao.getEventIds(groupId).toSet()
        val pendingIds = eventDao.getPendingEvents(groupId).mapTo(HashSet()) { it.eventId }
        val retryable = mutableListOf<NostrEvent>()
        // Every pull is historical catch-up. A relay's debt can be arbitrarily old even when the
        // caller requested an incremental pull. Use historical validation, never live rate limits;
        // otherwise a recovered batch could be rejected and then certified as covered.
        val context = IngestionContext.RECONCILIATION
        var count = 0
        for (event in events) {
            if (event.id in existingIds && event.id !in pendingIds) continue
            // A `p` tag addresses an event to one member: the creator signs one key_rotation envelope
            // per recipient, and a gift wrap names its recipient the same way. The #g filter returns
            // every member's copy. Another member's copy cannot be decrypted here, so it is not this
            // recipient's missing history and must not keep the relay cursor from advancing.
            if (!event.isAddressedTo(recipient)) continue
            // The recipient filter returns this member's envelopes for every group. Those tagged for
            // another group are that group's pull to ingest; the processor would refuse them here anyway,
            // since the group an event belongs to is its own signed tag, never the pull's.
            if (event.kind == NostrKind.GIFT_WRAP && event.groupTag()?.let { it != groupId } == true) continue
            val result =
                eventProcessor.process(
                    rawEvent = event,
                    knownGroupId = groupId,
                    lenientTimestamp = lenientTimestamp,
                    context = context
                )
            if (result.retryable || result.reason == "pending quota") retryable += event
            if (result.stored && event.id !in existingIds) {
                if (notifyContext != null && result.outcome == IngestOutcome.APPLIED) {
                    ExpenseNotifier.notifyIfNeeded(
                        notifyContext,
                        result.eventType!!,
                        result.decrypted,
                        result.authorHex!!,
                        identity.getPublicKeyHex(),
                        result.groupName ?: "Group"
                    )
                }
                count++
            }
        }
        // Dependencies may arrive later in the same fetch, including gift-wrapped controls whose
        // randomized outer timestamp is not their inner event order. Retry bounded by progress.
        eventProcessor.retryDeferred(groupId)
        for (pass in 0 until MAX_DEPENDENCY_PASSES) {
            if (retryable.isEmpty()) break
            var progressed = false
            val iterator = retryable.iterator()
            while (iterator.hasNext()) {
                val event = iterator.next()
                val result = eventProcessor.process(
                    event,
                    knownGroupId = groupId,
                    lenientTimestamp = lenientTimestamp,
                    context = context
                )
                if (result.outcome != IngestOutcome.REJECTED) {
                    iterator.remove()
                    progressed = true
                    if (result.stored) count++
                }
            }
            eventProcessor.retryDeferred(groupId)
            if (!progressed) break
        }
        // Persist only completed relay coverage, after processing, at fetch-start time. Failed or
        // excluded relays keep their old (or absent) cursor across workers and process restarts.
        // A rejected dependency/quota record is not durably stored. Without per-event relay
        // provenance, conservatively retain every window until all such evidence is accepted.
        val sameRecipient = identity.getPublicKeyHex() == recipient
        val completed = if (retryable.isEmpty() && sameRecipient) fetch.completedRelays else emptySet()
        relayCursors.advance(groupId, recipient, completed, startedAt)
        if (completed.isNotEmpty()) groupRepo.updateLastSync(groupId, startedAt)
        return PullResult(count, fetch.complete && retryable.isEmpty() && sameRecipient)
    }

    /**
     * Publish all due outbox events to connected relays.
     *
     * Rows are never evicted for failing to publish: the outbox holds user-authored content and
     * a relay outage must not silently discard it. See [isDue] for the back-off applied instead.
     */
    override suspend fun flushOutbox(): FlushResult {
        val pending = outboxDao.getAll()
        if (pending.isEmpty()) return FlushResult(0, 0)
        val now = System.currentTimeMillis() / 1000
        val due = pending.filter { isDue(it, now) }
        val backedOff = pending.size - due.size
        Log.i(TAG, "Flushing ${due.size} outbox events" + if (backedOff > 0) " ($backedOff backed off)" else "")
        var published = 0
        var failed = 0
        for (event in due) {
            if (nostrClient.publishJson(event.eventJson)) {
                outboxDao.delete(event.eventId)
                published++
            } else {
                failed++
                outboxDao.incrementRetry(event.eventId, now)
                if (event.retryCount >= WARN_RETRY_THRESHOLD) {
                    Log.w(TAG, "Event ${event.eventId} has failed ${event.retryCount} retries")
                }
            }
        }
        return FlushResult(published, failed)
    }

    /** Same due rule as [flushOutbox], so a drain can skip connecting when a flush would attempt nothing. */
    suspend fun hasDueOutbox(): Boolean {
        val now = System.currentTimeMillis() / 1000
        return outboxDao.getAll().any { isDue(it, now) }
    }

    /**
     * Whether [event] should be attempted in this flush pass.
     *
     * Below [MAX_RETRIES] every row is attempted. At or above it, a non-critical row is attempted
     * at most once per [STUCK_RETRY_INTERVAL_SECS] so a permanently rejected event cannot burn
     * the relay budget of every sync, while still getting a chance whenever relays change.
     * Critical types (`group_meta`, `key_rotation`, `key_revocation`) keep unlimited retries.
     */
    private fun isDue(event: OutboxEntity, now: Long): Boolean {
        if (event.retryCount < MAX_RETRIES) return true
        if (event.eventType in CRITICAL_TYPES) return true
        val lastRetry = event.lastRetryAt ?: return true
        return now - lastRetry >= STUCK_RETRY_INTERVAL_SECS
    }

    private fun NostrEvent.groupTag(): String? = tags.firstOrNull { it.size >= 2 && it[0] == "g" }?.get(1)

    companion object {
        private const val TAG = "SyncEngine"
        private const val MAX_DEPENDENCY_PASSES = 8
        private const val CURSOR_OVERLAP_SECS = 3600L
        private const val WARN_RETRY_THRESHOLD = 10

        /** Failed attempts after which a non-critical row is considered stuck and backed off. */
        const val MAX_RETRIES = 50

        /** Minimum spacing between attempts for a stuck row: 6 hours. */
        const val STUCK_RETRY_INTERVAL_SECS = 6 * 3600L

        /** Never backed off: losing these breaks group membership or key state for everyone. */
        val CRITICAL_TYPES = setOf("group_meta", "key_rotation", "key_revocation")
    }
}
