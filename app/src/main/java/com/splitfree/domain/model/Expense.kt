package com.splitfree.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SplitType {
    @SerialName("equal")
    EQUAL,

    @SerialName("exact")
    EXACT,

    @SerialName("percentage")
    PERCENTAGE,

    @SerialName("shares")
    SHARES,
}

@Serializable
data class SplitEntry(
    val pubkey: String,
    val share: Long, // amount in smallest currency unit (paise/cents)
)

@Serializable
data class Expense(
    val id: String,
    val amount: Long,
    val currency: String,
    val description: String,
    @SerialName("paid_by") val paidBy: String,
    @SerialName("split_type") val splitType: SplitType,
    @SerialName("split_among") val splitAmong: List<SplitEntry>,
    val timestamp: Long,
    val category: String = "",
)
