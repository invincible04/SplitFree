package com.splitfree.domain.usecase.group

import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.nip.Nip44
import com.splitfree.domain.model.group.KeyRotation
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.util.hexToBytes
import com.splitfree.util.DebugLog as Log
import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Removes a member via epoch-based key rotation (no group ID change).
 *
 * 1. Generate a new symmetric key for the next epoch
 * 2. Publish a `key_rotation` event encrypted with the CURRENT epoch key,
 *    containing per-member NIP-44 encrypted new keys
 * 3. Update local group: remove member, bump epoch, store new key
 *
 * Receivers decrypt the rotation event, store the new epoch key, and update
 * their local member list. History is preserved — old events decrypt with
 * their epoch's key.
 */
class RotateGroupKeyUseCase
@Inject
constructor(
    private val groupRepo: GroupRepositoryContract,
    private val encryption: GroupEncryption,
    private val identity: IdentityContract,
    private val signer: EventSigner,
    private val eventPublisher: EventPublisherContract
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @param groupId the group to rotate keys for
     * @param removePubkey the member pubkey to exclude
     * @throws IllegalStateException if caller is not the group creator or member not found
     */
    suspend operator fun invoke(groupId: String, removePubkey: String) {
        withContext(NonCancellable) {
            val group = groupRepo.getById(groupId) ?: error("Group $groupId not found")
            val myPubkey = identity.getPublicKeyHex()
            check(group.createdBy == myPubkey) { "Only the group creator can remove members" }
            check(removePubkey in group.members) { "Member not in group" }
            check(removePubkey != myPubkey) { "Cannot remove yourself" }

            val remainingMembers = group.members - removePubkey
            val newEpoch = group.keyEpoch + 1
            val newGroupKey = encryption.generateGroupKey()

            // Encrypt the new key individually for each remaining member
            val privKey = identity.getPrivateKeyBytes()
            val encryptedKeys = mutableMapOf<String, String>()
            try {
                for (memberPubHex in remainingMembers) {
                    val memberPubBytes = memberPubHex.hexToBytes()
                    val convKey = Nip44.getConversationKey(privKey, memberPubBytes)
                    encryptedKeys[memberPubHex] = Nip44.encrypt(newGroupKey, convKey)
                }
            } finally {
                privKey.fill(0)
            }

            // Publish key_rotation event encrypted per-member so the removed
            // member cannot decrypt the outer envelope and learn group metadata.
            // Each remaining member receives a dedicated event they can decrypt
            // with their NIP-44 conversation key.
            val rotationPayload = json.encodeToString(
                KeyRotation.serializer(),
                KeyRotation(
                    epoch = newEpoch,
                    encryptedKeys = encryptedKeys,
                    members = remainingMembers,
                    removedMember = removePubkey
                )
            )
            val privKeyForWrap = identity.getPrivateKeyBytes()
            try {
                for (memberPubHex in remainingMembers) {
                    val memberPubBytes = memberPubHex.hexToBytes()
                    val convKey = Nip44.getConversationKey(privKeyForWrap, memberPubBytes)
                    val perMemberEncrypted = Nip44.encrypt(rotationPayload, convKey)
                    val rotationEvent = signer.createSignedEvent(
                        groupId = groupId,
                        eventType = "key_rotation",
                        encryptedContent = perMemberEncrypted
                    )
                    eventPublisher.publishDirect(rotationEvent, groupId, perMemberEncrypted, "key_rotation")
                }
            } finally {
                privKeyForWrap.fill(0)
            }

            // Update local state
            groupRepo.saveGroupKeyForEpoch(groupId, newEpoch, newGroupKey)
            groupRepo.updateKeyEpoch(groupId, newEpoch)
            val updatedNames = group.memberNames.filterKeys { it in remainingMembers }
            groupRepo.updateFromMeta(
                groupId,
                group.name,
                remainingMembers,
                group.relays,
                memberNames = updatedNames
            )

            Log.i(TAG, "Rotated key for ${group.name}: epoch $newEpoch, removed ${removePubkey.take(8)}")
        }
    }

    /**
     * Handle an incoming key_rotation event — decrypt new key and update local state.
     */
    suspend fun handleKeyRotation(decryptedContent: String, authorPubkey: String, groupId: String) {
        val rotation = try {
            json.decodeFromString<KeyRotation>(decryptedContent)
        } catch (e: Exception) {
            Log.w(TAG, "Invalid key_rotation payload: ${e.message}")
            return
        }

        val group = groupRepo.getById(groupId) ?: return
        if (authorPubkey != group.createdBy) {
            Log.w(TAG, "Ignoring key_rotation from non-creator $authorPubkey")
            return
        }

        val myPubkey = identity.getPublicKeyHex()
        if (myPubkey !in rotation.members) {
            Log.i(TAG, "I was removed from group $groupId at epoch ${rotation.epoch}")
            // Update member list locally so UI reflects removal
            groupRepo.updateFromMeta(
                groupId,
                group.name,
                rotation.members,
                group.relays,
                memberNames = group.memberNames.filterKeys { it in rotation.members }
            )
            return
        }

        // Already processed this epoch
        if (group.keyEpoch >= rotation.epoch) return

        // Validate member list is a subset of current minus removed
        val expectedMembers = group.members.toSet() - rotation.removedMember
        if (!expectedMembers.containsAll(rotation.members.toSet())) {
            Log.w(TAG, "key_rotation contains members not in original group — rejecting")
            return
        }

        val myEncryptedKey = rotation.encryptedKeys[myPubkey]
        if (myEncryptedKey == null) {
            Log.w(TAG, "No encrypted key for me in key_rotation")
            return
        }

        val privKey = identity.getPrivateKeyBytes()
        val newGroupKey: String
        try {
            val creatorPubBytes = authorPubkey.hexToBytes()
            val convKey = Nip44.getConversationKey(privKey, creatorPubBytes)
            newGroupKey = Nip44.decrypt(myEncryptedKey, convKey)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decrypt new group key: ${e.message}")
            return
        } finally {
            privKey.fill(0)
        }

        groupRepo.saveGroupKeyForEpoch(groupId, rotation.epoch, newGroupKey)
        groupRepo.updateKeyEpoch(groupId, rotation.epoch)
        val updatedNames = group.memberNames.filterKeys { it in rotation.members }
        groupRepo.updateFromMeta(
            groupId,
            group.name,
            rotation.members,
            group.relays,
            memberNames = updatedNames
        )

        Log.i(TAG, "Applied key rotation for group $groupId: epoch ${rotation.epoch}")
    }

    companion object {
        private const val TAG = "RotateGroupKeyUseCase"
    }
}
