package com.splitfree.domain.model.balance

import kotlinx.serialization.Serializable

/**
 * Point-in-time balance snapshot for incremental computation.
 * Created every 100 events or 30 days so balance recalculation doesn't need to
 * replay the entire event history.
 *
 * @property as_of_timestamp creation time of the snapshot; informational only, readers decide which
 *   events to replay by [event_hashes] coverage, never by timestamp
 * @property event_hashes hashes of every event ID the creator had stored when snapshotting. New snapshots
 *   carry the first 24 hex chars of SHA-256(eventId) (see `HashUtil.eventHashPrefix`); older ones carry the
 *   full 64-char digest. Readers match on the 24-char prefix so both remain valid. Used to detect
 *   divergence and to decide which local events the snapshot already covers.
 */
@Serializable
data class BalanceSnapshot(
    val id: String,
    val as_of_event_count: Int,
    val as_of_timestamp: Long,
    val balances: List<SnapshotBalance>,
    val event_hashes: List<String>
)
