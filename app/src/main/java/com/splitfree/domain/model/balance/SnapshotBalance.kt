package com.splitfree.domain.model.balance

import kotlinx.serialization.Serializable

/**
 * Single member's balance within a [BalanceSnapshot].
 */
@Serializable
data class SnapshotBalance(val pubkey: String, val net: Long, val currency: String = "INR")
