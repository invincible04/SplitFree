package com.splitfree.domain.model.expense

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An expense shared among group members.
 *
 * @property amount total in smallest currency unit (e.g. paisa for INR, cents for USD)
 * @property paidBy pubkey of the member who paid
 * @property splitAmong per-member shares; sum must equal [amount]
 */
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
    val category: String = ""
)
