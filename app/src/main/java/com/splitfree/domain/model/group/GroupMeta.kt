package com.splitfree.domain.model.group

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Deserialized content of a `group_meta` event — the group's current state as published to relays.
 */
@Serializable
data class GroupMeta(
    val name: String = "",
    val description: String = "",
    @SerialName("created_by") val createdBy: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
    val members: List<String> = emptyList(),
    val relays: List<String> = emptyList(),
    @SerialName("member_names") val memberNames: Map<String, String> = emptyMap(),
    @SerialName("key_epoch") val keyEpoch: Int = 0
)
