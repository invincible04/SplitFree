package com.splitfree.domain.usecase.group

import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.nip.Nip44
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.model.group.KeyRotation
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.util.hexToBytes
import com.splitfree.sync.event.RotationOutcome
import com.splitfree.util.DebugLog as Log
import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Removes a member via epoch-based key rotation (no group ID change).
 *
 * 1. Generate a new symmetric key for the next epoch and persist it locally
 *    (durably, before anything is published)
 * 2. Publish a `key_rotation` event encrypted with the CURRENT epoch key,
 *    containing per-member NIP-44 encrypted new keys
 * 3. Update local group: remove member, bump epoch
 * 4. Publish a `group_meta` encrypted with the NEW epoch key so the latest
 *    metadata on relays no longer lists the removed member
 *
 * Receivers decrypt the rotation event, store the new epoch key, and update
 * their local member list. History is preserved; old events decrypt with
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
     * Serialises every epoch transition for this process. Two concurrent removals
     * (or a removal racing an incoming rotation) would otherwise both read epoch N
     * and both try to publish epoch N+1 with different keys.
     */
    private val mutex = Mutex()

    /**
     * @param groupId the group to rotate keys for
     * @param removePubkey the member pubkey to exclude
     * @throws IllegalStateException if caller is not the group creator, member not found,
     *   or the group's epoch changed underneath the rotation
     */
    suspend operator fun invoke(groupId: String, removePubkey: String) {
        withContext(NonCancellable) {
            mutex.withLock {
                rotate(groupId, removePubkey)
            }
        }
    }

    private suspend fun rotate(groupId: String, removePubkey: String) {
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

        // Persist the new epoch key BEFORE anything leaves the device. saveGroupKeyForEpoch
        // throws SecureStorageException if the Keystore write does not land, so we never
        // publish rotation events for a key we would then be unable to use ourselves.
        // The group's epoch is only advanced after publishing succeeds (below).
        groupRepo.saveGroupKeyForEpoch(groupId, newEpoch, newGroupKey)

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
                // The `p` tag names the one member who can open this envelope so relays and
                // nearby couriers can route it without decrypting it.
                val rotationEvent = signer.createSignedEvent(
                    groupId = groupId,
                    eventType = "key_rotation",
                    encryptedContent = perMemberEncrypted,
                    recipientPubkey = memberPubHex
                )
                eventPublisher.publishDirect(rotationEvent, groupId, perMemberEncrypted, "key_rotation")
            }
        } finally {
            privKeyForWrap.fill(0)
        }

        // Re-read right before mutating: if anything else advanced the epoch while we were
        // publishing, our rotation is no longer a clean N -> N+1 transition and must not land.
        val current = groupRepo.getById(groupId) ?: error("Group $groupId not found")
        check(current.keyEpoch == group.keyEpoch) { "Group changed during rotation" }

        // Update local state (key already stored above). eventTimestamp = 0 takes the
        // unconditional path, which also advances lastMetaTimestamp to now so a stale
        // group_meta replayed from a relay cannot re-add the removed member.
        groupRepo.updateKeyEpoch(groupId, newEpoch)
        val updatedNames = group.memberNames.filterKeys { it in remainingMembers }
        groupRepo.updateFromMeta(
            groupId,
            group.name,
            remainingMembers,
            group.relays,
            memberNames = updatedNames
        )

        // Publish a group_meta under the NEW epoch key. Without this the latest group_meta on
        // relays still lists the removed member, and any peer that only sees that meta (or a
        // fresh joiner) would resurrect them.
        val meta = GroupMeta(
            name = group.name,
            description = group.description,
            createdBy = myPubkey,
            createdAt = group.createdAt,
            members = remainingMembers,
            relays = group.relays,
            memberNames = updatedNames
        )
        val metaEncrypted = encryption.encrypt(json.encodeToString(GroupMeta.serializer(), meta), newGroupKey)
        val metaEvent = signer.createSignedEvent(
            groupId = groupId,
            eventType = "group_meta",
            encryptedContent = metaEncrypted
        )
        eventPublisher.publishDirect(metaEvent, groupId, metaEncrypted, "group_meta")

        Log.i(TAG, "Rotated key for ${group.name}: epoch $newEpoch, removed ${removePubkey.take(8)}")
    }

    /**
     * Handle an incoming key_rotation event: decrypt new key and update local state.
     *
     * @param createdAt the rotation event's `created_at`, used as the LWW timestamp for the
     *   member-list update so a stale `group_meta` cannot revert it
     * @return [RotationOutcome.APPLIED] once the epoch key is installed (or my own removal recorded),
     *   [RotationOutcome.DEFERRED_EPOCH_GAP] when an earlier epoch has not landed yet (caller keeps the
     *   row pending and retries), [RotationOutcome.IGNORED] for a replay of the current or an older
     *   epoch, [RotationOutcome.REJECTED] for anything malformed, unauthorized or inconsistent
     */
    suspend fun handleKeyRotation(
        decryptedContent: String,
        authorPubkey: String,
        groupId: String,
        createdAt: Long
    ): RotationOutcome {
        val rotation = try {
            json.decodeFromString<KeyRotation>(decryptedContent)
        } catch (e: Exception) {
            Log.w(TAG, "Invalid key_rotation payload: ${e.message}")
            return RotationOutcome.REJECTED
        }

        return mutex.withLock {
            applyKeyRotation(rotation, authorPubkey, groupId, createdAt)
        }
    }

    private suspend fun applyKeyRotation(
        rotation: KeyRotation,
        authorPubkey: String,
        groupId: String,
        createdAt: Long
    ): RotationOutcome {
        val group = groupRepo.getById(groupId) ?: return RotationOutcome.REJECTED
        if (authorPubkey != group.createdBy) {
            Log.w(TAG, "Ignoring key_rotation from non-creator $authorPubkey")
            return RotationOutcome.REJECTED
        }

        // Already processed this epoch (or a later one). This must run BEFORE the
        // "I was removed" branch: a replayed old rotation that predates our re-invite
        // would otherwise remove us again.
        if (group.keyEpoch >= rotation.epoch) return RotationOutcome.IGNORED

        // Rotations must be applied strictly in sequence. Skipping an epoch would leave the
        // intermediate history undecryptable (we would never receive that epoch's key), so the
        // row stays pending until the missing epoch arrives (relay catch-up or a nearby peer).
        if (rotation.epoch != group.keyEpoch + 1) {
            Log.w(
                TAG,
                "Deferring key_rotation for epoch ${rotation.epoch} in $groupId, local epoch is ${group.keyEpoch}"
            )
            return RotationOutcome.DEFERRED_EPOCH_GAP
        }

        // The removed member must be someone we actually know about (or unspecified).
        if (rotation.removedMember.isNotEmpty() && rotation.removedMember !in group.members) {
            Log.w(TAG, "key_rotation removes ${rotation.removedMember.take(8)} who is not a member, rejecting")
            return RotationOutcome.REJECTED
        }

        val myPubkey = identity.getPublicKeyHex()
        if (myPubkey !in rotation.members) {
            Log.i(TAG, "I was removed from group $groupId at epoch ${rotation.epoch}")
            // Update member list locally so UI reflects removal. Ordering is enforced by the epoch
            // checks above, so this takes the unconditional path: a same-second group_meta must not
            // be able to keep a removed member in the roster.
            groupRepo.updateFromMeta(
                groupId,
                group.name,
                rotation.members,
                group.relays,
                memberNames = group.memberNames.filterKeys { it in rotation.members }
            )
            return RotationOutcome.APPLIED
        }

        // Validate member list is a subset of current minus removed
        val expectedMembers = group.members.toSet() - rotation.removedMember
        if (!expectedMembers.containsAll(rotation.members.toSet())) {
            Log.w(TAG, "key_rotation contains members not in original group, rejecting")
            return RotationOutcome.REJECTED
        }

        val myEncryptedKey = rotation.encryptedKeys[myPubkey]
        if (myEncryptedKey == null) {
            Log.w(TAG, "No encrypted key for me in key_rotation")
            return RotationOutcome.REJECTED
        }

        val privKey = identity.getPrivateKeyBytes()
        val newGroupKey: String
        try {
            val creatorPubBytes = authorPubkey.hexToBytes()
            val convKey = Nip44.getConversationKey(privKey, creatorPubBytes)
            newGroupKey = Nip44.decrypt(myEncryptedKey, convKey)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decrypt new group key: ${e.message}")
            return RotationOutcome.REJECTED
        } finally {
            privKey.fill(0)
        }

        // Key material for an epoch is immutable once written. If this device already holds a
        // key for `rotation.epoch` (crash between saveGroupKeyForEpoch and updateKeyEpoch, or a
        // second per-member envelope of the same rotation), it must be byte-identical: a different
        // key would mean two rotations claim the same epoch and we must not silently overwrite.
        val existing = groupRepo.getGroupKeyForEpoch(groupId, rotation.epoch)
        if (existing != null && existing != newGroupKey) {
            Log.w(TAG, "key_rotation for epoch ${rotation.epoch} in $groupId carries conflicting key material")
            return RotationOutcome.REJECTED
        }
        if (existing == null) {
            groupRepo.saveGroupKeyForEpoch(groupId, rotation.epoch, newGroupKey)
        } else {
            Log.i(TAG, "Epoch ${rotation.epoch} key already stored for $groupId, resuming interrupted rotation")
        }
        groupRepo.updateKeyEpoch(groupId, rotation.epoch)
        // Epoch order is already enforced above; the roster change is applied unconditionally, as
        // the creator does locally, so a group_meta stamped in the same second cannot block it.
        val updatedNames = group.memberNames.filterKeys { it in rotation.members }
        groupRepo.updateFromMeta(
            groupId,
            group.name,
            rotation.members,
            group.relays,
            memberNames = updatedNames
        )

        Log.i(TAG, "Applied key rotation for group $groupId: epoch ${rotation.epoch}")
        return RotationOutcome.APPLIED
    }

    companion object {
        private const val TAG = "RotateGroupKeyUseCase"
    }
}
