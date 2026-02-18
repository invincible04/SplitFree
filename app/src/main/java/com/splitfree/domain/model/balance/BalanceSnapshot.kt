package com.splitfree.domain.model.balance

import kotlinx.serialization.Serializable

/**
 * Point-in-time balance snapshot for incremental computation.
 * Created every 100 events or 30 days so balance recalculation doesn't need to
 * replay the entire event history.
 *
 * @property event_hashes SHA-256 hashes of event IDs at snapshot time, used to detect divergence
 */
@Serializable
data class BalanceSnapshot(
    val id: String,
    val as_of_event_count: Int,
    val as_of_timestamp: Long,
    val balances: List<SnapshotBalance>,
    val event_hashes: List<String>
)
