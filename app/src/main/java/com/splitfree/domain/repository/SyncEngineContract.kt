package com.splitfree.domain.repository

import com.splitfree.domain.model.sync.FlushResult
import com.splitfree.domain.model.sync.PullResult

/**
 * Domain contract for sync engine operations.
 * Coordinates pulling events from relays and flushing the local outbox.
 */
interface SyncEngineContract {
    /**
     * Pull events for a group from connected relays and process new ones. The group's sync cursor
     * advances only when the fetch was complete.
     *
     * @param groupId target group UUID
     * @param since unix timestamp to fetch events after
     * @param groupKey base64-encoded symmetric group key for decryption
     * @param lenientTimestamp if true, allows events older than 30 days (for initial/full sync)
     */
    suspend fun pullEvents(
        groupId: String,
        since: Long,
        groupKey: String,
        lenientTimestamp: Boolean = false
    ): PullResult

    /** Publish all due outbox events to connected relays. */
    suspend fun flushOutbox(): FlushResult
}
