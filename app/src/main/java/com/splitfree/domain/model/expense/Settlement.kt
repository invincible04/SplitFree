package com.splitfree.domain.model.expense

import kotlinx.serialization.Serializable

/**
 * A debt settlement between two members (e.g. cash payment, bank transfer).
 *
 * @property from pubkey of the member who paid
 * @property to pubkey of the member who received
 * @property amount in smallest currency unit
 */
@Serializable
data class Settlement(
    val id: String,
    val from: String,
    val to: String,
    val amount: Long,
    val currency: String,
    val method: String = "cash",
    val timestamp: Long
)
