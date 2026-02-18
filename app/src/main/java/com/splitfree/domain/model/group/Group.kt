package com.splitfree.domain.model.group

import kotlinx.serialization.Serializable

/**
 * An expense group — the core organizational unit in SplitFree.
 *
 * @property id UUID, also used as the Nostr `g` tag for event filtering
 * @property createdBy pubkey of the group creator (has elevated privileges for group_meta)
 * @property members list of member pubkeys
 * @property relays Nostr relay URLs where this group's events are published
 */
@Serializable
data class Group(
    val id: String,
    val name: String,
    val description: String = "",
    val createdBy: String,
    val createdAt: Long,
    val members: List<String>,
    val relays: List<String>
)
