package com.splitfree.sync.worker

import android.content.Context
import com.splitfree.data.local.dao.EventDao
import com.splitfree.data.local.dao.OutboxDao
import com.splitfree.data.local.entities.OutboxEntity
import com.splitfree.data.nostr.NostrClient
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.NostrKind
import com.splitfree.domain.model.sync.FlushResult
import com.splitfree.domain.model.sync.PullResult
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.SyncEngineContract
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
    private val eventProcessor: EventProcessor
) : SyncEngineContract {
    /**
     * Pull events for a group from connected relays and process new ones. The group's cursor moves
     * only after a complete fetch, and then to the time the fetch began.
     *
     * @param groupId target group UUID
     * @param since unix timestamp to fetch events after
     * @param groupKey base64-encoded symmetric group key for decryption
     * @param lenientTimestamp if true, allows events older than 30 days
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
        val fetch = nostrClient.fetchEvents(groupId, since, identity.getPublicKeyHex())
        val events = fetch.events.sortedWith(compareBy<NostrEvent> { it.createdAt }.thenBy { it.id })
        val existingIds = eventDao.getEventIds(groupId).toSet()
        val pendingIds = eventDao.getPendingEvents(groupId).mapTo(HashSet()) { it.eventId }
        val retryable = mutableListOf<NostrEvent>()
        // A full/initial pull is an explicit history catch-up: historical timestamps are expected and
        // the in-memory rate counters must not throttle it.
        val context = if (lenientTimestamp) IngestionContext.RECONCILIATION else IngestionContext.LIVE
        var count = 0
        for (event in events) {
            if (event.id in existingIds && event.id !in pendingIds) continue
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
            if (result.retryable) retryable += event
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
        repeat(MAX_DEPENDENCY_PASSES) {
            if (retryable.isEmpty()) return@repeat
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
            if (!progressed) retryable.clear()
        }
        // startedAt, not now: anything published while the fetch was in flight may be missing from
        // its result, and a "now" cursor would step over it. An incomplete fetch leaves the cursor
        // alone so the next pull re-covers the same window.
        if (fetch.complete) {
            groupRepo.updateLastSync(groupId, startedAt)
        }
        return PullResult(count, fetch.complete)
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
        private const val WARN_RETRY_THRESHOLD = 10

        /** Failed attempts after which a non-critical row is considered stuck and backed off. */
        const val MAX_RETRIES = 50

        /** Minimum spacing between attempts for a stuck row: 6 hours. */
        const val STUCK_RETRY_INTERVAL_SECS = 6 * 3600L

        /** Never backed off: losing these breaks group membership or key state for everyone. */
        val CRITICAL_TYPES = setOf("group_meta", "key_rotation", "key_revocation")
    }
}
