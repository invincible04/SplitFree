package com.splitfree.domain.model.group

import kotlinx.serialization.Serializable

/**
 * Deserialized content of a `group_migrate` event.
 *
 * Published to the old group when a member is removed. Contains the new group ID,
 * per-member encrypted keys (NIP-44), and the updated member list.
 *
 * @property encryptedKeys map of member pubkey → NIP-44 encrypted new group key
 * @property removedMember pubkey of the member being excluded
 */
@Serializable
data class GroupMigration(
    val newGroupId: String,
    val encryptedKeys: Map<String, String>,
    val members: List<String>,
    val removedMember: String
)
