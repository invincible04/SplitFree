package com.splitfree.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GroupMeta(
    val name: String = "",
    val description: String = "",
    @SerialName("created_by") val createdBy: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
    val members: List<String> = emptyList(),
    val relays: List<String> = emptyList()
)
