package com.splitfree.sync.worker

import android.content.Context
import com.splitfree.data.local.dao.EventDao
import com.splitfree.data.local.dao.OutboxDao
import com.splitfree.data.nostr.NostrClient
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.SyncEngineContract
import com.splitfree.sync.event.EventProcessor
import com.splitfree.sync.event.ExpenseNotifier
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
     * Pull events for a group from connected relays and process new ones.
     *
     * @param groupId target group UUID
     * @param since unix timestamp to fetch events after
     * @param groupKey base64-encoded symmetric group key for decryption
     * @param lenientTimestamp if true, allows events older than 30 days
     * @param notifyContext if non-null, shows local notifications for incoming expenses
     * @return number of new events stored
     */
    override suspend fun pullEvents(groupId: String, since: Long, groupKey: String, lenientTimestamp: Boolean): Int =
        pullEvents(groupId, since, groupKey, lenientTimestamp, notifyContext = null)

    /**
     * Pull events with optional notification support (data layer only).
     *
     * @param notifyContext if non-null, shows local notifications for incoming expenses
     */
    suspend fun pullEvents(
        groupId: String,
        since: Long,
        groupKey: String,
        lenientTimestamp: Boolean = false,
        notifyContext: Context? = null
    ): Int {
        // Relays return newest-first. key_rotation must be applied strictly in epoch order and
        // group_meta is last-writer-wins on created_at, so process a catch-up batch oldest-first.
        val events = nostrClient.fetchEvents(groupId, since, identity.getPublicKeyHex()).sortedBy { it.createdAt }
        val existingIds = eventDao.getEventIds(groupId).toSet()
        var count = 0
        for (event in events) {
            if (event.id in existingIds) continue
            val result =
                eventProcessor.process(
                    rawEvent = event,
                    knownGroupId = groupId,
                    knownGroupKey = groupKey,
                    lenientTimestamp = lenientTimestamp
                )
            if (result.stored) {
                if (notifyContext != null) {
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
        if (count > 0) {
            groupRepo.updateLastSync(groupId, System.currentTimeMillis() / 1000)
        }
        return count
    }

    /**
     * Publish all pending outbox events to connected relays.
     *
     * @return number of successfully published events
     */
    override suspend fun flushOutbox(): Int {
        val pending = outboxDao.getAll()
        if (pending.isEmpty()) return 0
        Log.i(TAG, "Flushing ${pending.size} outbox events")
        var published = 0
        for (event in pending) {
            if (nostrClient.publishJson(event.eventJson)) {
                outboxDao.delete(event.eventId)
                published++
            } else {
                outboxDao.incrementRetry(event.eventId, System.currentTimeMillis() / 1000)
                // Never evict critical events (group_meta, key_rotation, key_revocation)
                val isCritical = event.eventType in setOf("group_meta", "key_rotation", "key_revocation")
                if (!isCritical && event.retryCount >= MAX_RETRIES) {
                    Log.w(TAG, "Evicting event ${event.eventId} after ${event.retryCount} failed retries")
                    outboxDao.delete(event.eventId)
                } else if (event.retryCount >= WARN_RETRY_THRESHOLD) {
                    Log.w(TAG, "Event ${event.eventId} has failed ${event.retryCount} retries")
                }
            }
        }
        return published
    }

    companion object {
        private const val TAG = "SyncEngine"
        private const val WARN_RETRY_THRESHOLD = 10
        private const val MAX_RETRIES = 50
    }
}
