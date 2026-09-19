package com.splitfree.domain.model.group

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Claimed group state carried by a `group_meta` event.
 * Authenticated creator metadata can update shared fields; other members can update only their own
 * membership and name. Creator certificates are verified independently of the metadata author.
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
    @SerialName("key_epoch") val keyEpoch: Int = 0,
    @SerialName("original_creator") val originalCreator: String = "",
    @SerialName("creator_transitions") val creatorTransitions: List<CreatorTransition> = emptyList()
)
