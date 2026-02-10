package com.splitfree.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Settlement(
    val id: String, // UUID
    val from: String, // pubkey
    val to: String, // pubkey
    val amount: Long, // smallest currency unit
    val currency: String,
    val method: String = "cash",
    val timestamp: Long
)