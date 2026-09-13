package com.splitfree.domain.usecase.group

import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.nip.Nip44
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.model.group.KeyRotation
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.EventRepositoryContract
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
 * Creator side, in order, each step idempotent so an interrupted rotation can be resumed:
 * 1. Key material for epoch N+1 is generated once and persisted before anything leaves the device.
 *    If a key for N+1 already exists (an earlier attempt was interrupted) it is reused: the key for
 *    an epoch is immutable, so every member ends up with the same one however many attempts it took.
 * 2. One `key_rotation` event per remaining member, encrypted to that member with NIP-44 and tagged
 *    `p` with the recipient, is published (durably queued, then dispatched).
 * 3. The local epoch advance and the new roster land in one statement ([GroupRepositoryContract.applyKeyRotation]).
 * 4. A `group_meta` under the new key is published so the latest metadata no longer lists the member.
 *
 * A crash between 2 and 3 leaves the creator at epoch N with envelopes already out. [resumeIfNeeded]
 * (called at start-up) recognises that state from the stored key for N+1 and the creator's own
 * `key_rotation` rows, re-publishes any envelope that never left, and finishes steps 3 and 4.
 *
 * Receivers ([handleKeyRotation]) install the epoch key (immutable once written) and apply epoch and
 * roster atomically. Rotations are ordered strictly by epoch and never touch the creator's metadata
 * watermark; creator metas are epoch-scoped for the roster instead (see [GroupRepositoryContract.updateFromMeta]).
 */
class RotateGroupKeyUseCase
@Inject
constructor(
    private val groupRepo: GroupRepositoryContract,
    private val encryption: GroupEncryption,
    private val identity: IdentityContract,
    private val signer: EventSigner,
    private val eventPublisher: EventPublisherContract,
    private val eventRepo: EventRepositoryContract
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

        // Persist the new epoch key BEFORE anything leaves the device. saveGroupKeyForEpoch throws
        // SecureStorageException if the Keystore write does not land, so we never publish rotation
        // events for a key we would then be unable to use ourselves. A key already stored for this
        // epoch belongs to an interrupted attempt and is reused: epoch key material is immutable.
        val newGroupKey =
            groupRepo.getGroupKeyForEpoch(groupId, newEpoch)?.also {
                Log.i(TAG, "Reusing stored key for epoch $newEpoch of ${group.name} (interrupted rotation)")
            } ?: encryption.generateGroupKey().also { groupRepo.saveGroupKeyForEpoch(groupId, newEpoch, it) }

        val rotation =
            KeyRotation(
                epoch = newEpoch,
                encryptedKeys = encryptKeyForMembers(newGroupKey, remainingMembers),
                members = remainingMembers,
                removedMember = removePubkey
            )
        publishRotation(groupId, rotation, remainingMembers)

        // Re-read right before mutating: if anything else advanced the epoch while we were
        // publishing, our rotation is no longer a clean N -> N+1 transition and must not land.
        val current = groupRepo.getById(groupId) ?: error("Group $groupId not found")
        check(current.keyEpoch == group.keyEpoch) { "Group changed during rotation" }

        finishLocally(current, rotation, newGroupKey, myPubkey)
        Log.i(TAG, "Rotated key for ${group.name}: epoch $newEpoch, removed ${removePubkey.take(8)}")
    }

    /**
     * Finish a rotation whose envelopes were published but whose local transition never landed
     * (crash between publish and [GroupRepositoryContract.applyKeyRotation]). Detected from a stored
     * key for `keyEpoch + 1` on a group this device created. Envelopes that were never stored are
     * re-published with the same key; then the epoch, roster and post-rotation `group_meta` land.
     * A stored key with no published envelope is left alone: nothing was distributed, and the next
     * removal reuses it.
     */
    suspend fun resumeIfNeeded() {
        withContext(NonCancellable) {
            mutex.withLock {
                val me = identity.getPublicKeyHex()
                for (group in groupRepo.getAll()) {
                    if (group.createdBy != me) continue
                    try {
                        resumeGroup(group, me)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not resume rotation for ${group.id.take(8)}: ${e.message}")
                    }
                }
            }
        }
    }

    private suspend fun resumeGroup(group: Group, me: String) {
        val nextEpoch = group.keyEpoch + 1
        val key = groupRepo.getGroupKeyForEpoch(group.id, nextEpoch) ?: return
        val mine =
            eventRepo.getEventsByType(group.id, "key_rotation")
                .filter { it.pubkey == me && it.originalEventJson != null }
                .mapNotNull { row -> NostrEvent.fromJson(checkNotNull(row.originalEventJson)) }
        val privKey = identity.getPrivateKeyBytes()
        var rotation: KeyRotation? = null
        val delivered = HashSet<String>()
        try {
            for (event in mine.asReversed()) {
                val recipient = event.tags.firstOrNull { it.size >= 2 && it[0] == "p" }?.get(1) ?: continue
                val payload =
                    try {
                        val convKey = Nip44.getConversationKey(privKey, recipient.hexToBytes())
                        json.decodeFromString<KeyRotation>(Nip44.decrypt(event.content, convKey))
                    } catch (_: Exception) {
                        continue
                    }
                if (payload.epoch != nextEpoch) continue
                if (rotation == null) rotation = payload
                delivered += recipient
            }
        } finally {
            privKey.fill(0)
        }
        val found = rotation ?: return // key stored, nothing published: harmless, reused next time
        Log.w(TAG, "Resuming interrupted rotation to epoch $nextEpoch for ${group.name}")
        publishRotation(group.id, found, found.members.filter { it !in delivered })
        finishLocally(group, found, key, me)
    }

    private fun encryptKeyForMembers(groupKey: String, members: List<String>): Map<String, String> {
        val privKey = identity.getPrivateKeyBytes()
        val encryptedKeys = mutableMapOf<String, String>()
        try {
            for (memberPubHex in members) {
                val convKey = Nip44.getConversationKey(privKey, memberPubHex.hexToBytes())
                encryptedKeys[memberPubHex] = Nip44.encrypt(groupKey, convKey)
            }
        } finally {
            privKey.fill(0)
        }
        return encryptedKeys
    }

    /**
     * Publish one `key_rotation` per recipient, encrypted to that member with the NIP-44 conversation
     * key so the removed member cannot read the envelope. The `p` tag names the one member who can
     * open it so relays and nearby couriers can route it without decrypting it.
     */
    private suspend fun publishRotation(groupId: String, rotation: KeyRotation, recipients: List<String>) {
        if (recipients.isEmpty()) return
        val payload = json.encodeToString(KeyRotation.serializer(), rotation)
        val privKey = identity.getPrivateKeyBytes()
        try {
            for (memberPubHex in recipients) {
                val convKey = Nip44.getConversationKey(privKey, memberPubHex.hexToBytes())
                val perMemberEncrypted = Nip44.encrypt(payload, convKey)
                val rotationEvent =
                    signer.createSignedEvent(
                        groupId = groupId,
                        eventType = "key_rotation",
                        encryptedContent = perMemberEncrypted,
                        recipientPubkey = memberPubHex
                    )
                eventPublisher.publishDirect(rotationEvent, groupId, perMemberEncrypted, "key_rotation")
            }
        } finally {
            privKey.fill(0)
        }
    }

    /** Steps 3 and 4: atomic local transition, then the post-rotation `group_meta` under the new key. */
    private suspend fun finishLocally(group: Group, rotation: KeyRotation, newGroupKey: String, myPubkey: String) {
        val updatedNames = group.memberNames.filterKeys { it in rotation.members }
        groupRepo.applyKeyRotation(group.id, rotation.epoch, rotation.members, updatedNames)

        // Without this the latest group_meta on relays still lists the removed member, and any peer
        // that only sees that meta (or a fresh joiner) would resurrect them.
        val meta =
            GroupMeta(
                name = group.name,
                description = group.description,
                createdBy = myPubkey,
                createdAt = group.createdAt,
                members = rotation.members,
                relays = group.relays,
                memberNames = updatedNames
            )
        val metaEncrypted = encryption.encrypt(json.encodeToString(GroupMeta.serializer(), meta), newGroupKey)
        val metaEvent =
            signer.createSignedEvent(
                groupId = group.id,
                eventType = "group_meta",
                encryptedContent = metaEncrypted
            )
        eventPublisher.publishDirect(metaEvent, group.id, metaEncrypted, "group_meta")
    }

    /**
     * Handle an incoming key_rotation event: decrypt new key and update local state.
     *
     * @param createdAt the rotation event's `created_at` (diagnostics only; ordering is by epoch)
     * @return [RotationOutcome.APPLIED] once the epoch and roster are installed (or my own removal
     *   recorded), [RotationOutcome.DEFERRED_EPOCH_GAP] when an earlier epoch has not landed yet,
     *   [RotationOutcome.DEFERRED_MEMBERSHIP] when it names a member whose join has not landed yet
     *   (caller keeps the row pending and retries both), [RotationOutcome.IGNORED] for a replay of the
     *   current or an older epoch, [RotationOutcome.REJECTED] for anything malformed, unauthorized or
     *   inconsistent with key material already installed
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

        // The roster after rotation may not introduce anyone this device has not seen join: that is
        // a missing earlier update (their group_meta), not an invalid rotation.
        val known = group.members.toSet()
        val unseen = rotation.members.filter { it !in known }
        if (unseen.isNotEmpty()) {
            Log.w(TAG, "Deferring key_rotation for epoch ${rotation.epoch}: ${unseen.size} member(s) not yet joined")
            return RotationOutcome.DEFERRED_MEMBERSHIP
        }
        if (rotation.removedMember.isNotEmpty() && rotation.removedMember !in known) {
            Log.i(TAG, "key_rotation removes ${rotation.removedMember.take(8)} whom this device never saw")
        }

        val myPubkey = identity.getPublicKeyHex()
        val updatedNames = group.memberNames.filterKeys { it in rotation.members }
        if (myPubkey !in rotation.members) {
            Log.i(TAG, "I was removed from group $groupId at epoch ${rotation.epoch} (created_at $createdAt)")
            // Advance the epoch without the key: this device can no longer read or write the group,
            // and a group_meta encrypted under the old epoch cannot re-add it.
            groupRepo.applyKeyRotation(groupId, rotation.epoch, rotation.members, updatedNames)
            return RotationOutcome.APPLIED
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
        // key for `rotation.epoch` (crash between saveGroupKeyForEpoch and applyKeyRotation, or a
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
        // Epoch and roster land together: a crash can never leave the new epoch with the old roster.
        groupRepo.applyKeyRotation(groupId, rotation.epoch, rotation.members, updatedNames)

        Log.i(TAG, "Applied key rotation for group $groupId: epoch ${rotation.epoch}")
        return RotationOutcome.APPLIED
    }

    companion object {
        private const val TAG = "RotateGroupKeyUseCase"
    }
}
