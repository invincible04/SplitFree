package com.splitfree.domain.model.balance

import kotlinx.serialization.Serializable

/**
 * Single member's balance within a [BalanceSnapshot].
 *
 * `currency` keeps its `"INR"` default deliberately: this is a wire type, and snapshots are encoded
 * with a `Json` that does not `encodeDefaults`, so every INR balance ever published omits the field.
 * Removing the default would make all existing snapshots on relays undecodable. Writers must still
 * pass the currency explicitly (see `CreateSnapshotUseCase`).
 */
@Serializable
data class SnapshotBalance(val pubkey: String, val net: Long, val currency: String = "INR")
