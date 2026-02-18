package com.splitfree.domain.model.expense

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How an expense is divided among members.
 */
@Serializable
enum class SplitType {
    @SerialName("equal") EQUAL,

    @SerialName("exact") EXACT,

    @SerialName("percentage") PERCENTAGE,

    @SerialName("shares") SHARES
}

/**
 * A single member's share in an expense split.
 */
@Serializable
data class SplitEntry(val pubkey: String, val share: Long)
