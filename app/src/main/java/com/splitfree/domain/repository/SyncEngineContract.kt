package com.splitfree.domain.repository

import com.splitfree.domain.model.sync.FlushResult
import com.splitfree.domain.model.sync.PullResult

/**
 * Domain contract for sync engine operations.
 * Coordinates pulling events from relays and flushing the local outbox.
 */
interface SyncEngineContract {
    /**
     * Pull events for a group from connected relays and process new ones. Each relay's history cursor
     * advances only after that relay completes; unavailable history remains durable recovery debt.
     *
     * @param groupId target group UUID
     * @param since requested unix timestamp; widened as needed to cover each relay's unresolved history
     * @param groupKey base64-encoded symmetric group key for decryption
     * @param lenientTimestamp retained for callers; historical pulls always permit old events
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
