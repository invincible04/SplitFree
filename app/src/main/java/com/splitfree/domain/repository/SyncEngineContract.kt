package com.splitfree.domain.repository

import com.splitfree.domain.model.sync.FlushResult
import com.splitfree.domain.model.sync.PullResult

/**
 * Domain contract for sync engine operations.
 * Coordinates pulling events from relays and flushing the local outbox.
 */
interface SyncEngineContract {
    /**
     * Resume bounded full-history sweeps for a group. Unfinished partitions survive process restart.
     * Completion is relative to relay EOSE responses, not proof of arrival-time coverage or retention.
     *
     * @param groupId target group UUID
     * @param since legacy caller hint; cannot exclude older authored events from reconciliation
     * @param groupKey base64-encoded symmetric group key for decryption
     * @param lenientTimestamp has no effect on admission: every pull is historical and always permits old events
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
