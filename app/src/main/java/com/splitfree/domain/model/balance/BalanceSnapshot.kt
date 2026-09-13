package com.splitfree.domain.model.balance

import kotlinx.serialization.Serializable

/**
 * Point-in-time balance snapshot for incremental computation.
 * Created every 100 events or 30 days so balance recalculation doesn't need to
 * replay the entire event history.
 *
 * @property as_of_timestamp creation time of the snapshot; informational only, readers decide which
 *   events to replay by [event_hashes] coverage, never by timestamp
 * @property as_of_event_count number of entries in [event_hashes]; readers reject a snapshot whose count differs
 * @property event_hashes `HashUtil.eventHashPrefix` (first 24 hex chars of SHA-256) of every event ID the
 *   creator had stored when snapshotting. Readers seed balances only when each hash matches exactly one local
 *   event and replay the whole local ledger otherwise; local events outside the list are replayed on top.
 */
@Serializable
data class BalanceSnapshot(
    val id: String,
    val as_of_event_count: Int,
    val as_of_timestamp: Long,
    val balances: List<SnapshotBalance>,
    val event_hashes: List<String>
)
