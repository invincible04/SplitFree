package com.splitfree.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Group(
    val id: String, // UUID
    val name: String,
    val description: String = "",
    val createdBy: String, // pubkey
    val createdAt: Long, // unix seconds
    val members: List<String>, // pubkeys
    val relays: List<String>
)