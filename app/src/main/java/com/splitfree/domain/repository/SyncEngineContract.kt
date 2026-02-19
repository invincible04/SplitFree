package com.splitfree.domain.repository

/**
 * Domain contract for sync engine operations.
 * Coordinates pulling events from relays and flushing the local outbox.
 */
interface SyncEngineContract {
    /**
     * Pull events for a group from connected relays and process new ones.
     *
     * @param groupId target group UUID
     * @param since unix timestamp to fetch events after
     * @param groupKey base64-encoded symmetric group key for decryption
     * @param lenientTimestamp if true, allows events older than 30 days (for initial/full sync)
     * @return number of new events stored
     */
    suspend fun pullEvents(groupId: String, since: Long, groupKey: String, lenientTimestamp: Boolean = false): Int

    /**
     * Publish all pending outbox events to connected relays.
     * @return number of successfully published events
     */
    suspend fun flushOutbox(): Int
}
