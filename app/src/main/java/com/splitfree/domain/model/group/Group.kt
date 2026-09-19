package com.splitfree.domain.model.group

import kotlinx.serialization.Serializable

/**
 * An expense group, the core organizational unit in SplitFree.
 *
 * @property id UUID, also used as the Nostr `g` tag for event filtering
 * @property createdBy current creator pubkey with group-metadata authority, or empty when unresolved
 * @property members list of member pubkeys
 * @property relays Nostr relay URLs where this group's events are published
 * @property memberNames optional map of member pubkey -> display name
 * @property originalCreator original creator bound to [id] and [createdAt]; unchanged by creator replacement
 * @property creatorTransitions retained portable authority evidence, including known conflicting forks
 */
@Serializable
data class Group(
    val id: String,
    val name: String,
    val description: String = "",
    val createdBy: String,
    val createdAt: Long,
    val members: List<String>,
    val relays: List<String>,
    val memberNames: Map<String, String> = emptyMap(),
    val keyEpoch: Int = 0,
    val originalCreator: String = createdBy,
    val creatorTransitions: List<CreatorTransition> = emptyList()
)
