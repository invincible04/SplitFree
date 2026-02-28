package com.splitfree.domain.model.group

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Deserialized content of a `key_rotation` event.
 *
 * Published to the group when a member is removed. The group ID stays the same;
 * only the symmetric key is rotated to a new epoch.
 *
 * @property epoch the new key epoch number
 * @property encryptedKeys map of remaining member pubkey → NIP-44 encrypted new group key
 * @property members updated member list (without the removed member)
 * @property removedMember pubkey of the member being excluded
 */
@Serializable
data class KeyRotation(
    val epoch: Int,
    @SerialName("encrypted_keys") val encryptedKeys: Map<String, String>,
    val members: List<String>,
    @SerialName("removed_member") val removedMember: String
)
