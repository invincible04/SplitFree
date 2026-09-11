package com.splitfree.domain.usecase.group

import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.model.group.KeyRevocation
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.util.DebugLog as Log
import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Key revocation: publish a signed "key_revocation" event to all groups,
 * then generate a new identity and re-join all groups with the new pubkey.
 *
 * Flow:
 * 1. Generate new keypair (pending, alongside the old one) and record the start time
 * 2. Build a `key_revocation` and an updated `group_meta` for every group, signed with the OLD key
 * 3. Publish everything
 * 4. Only then swap old -> new pubkey in local membership, record the event IDs, and commit the new key
 *
 * Receivers: when they see a key_revocation event, they update the member list
 * to replace oldPubkey with newPubkey.
 */
class RevokeKeyUseCase
@Inject
constructor(
    private val identity: IdentityContract,
    private val groupRepo: GroupRepositoryContract,
    private val encryption: GroupEncryption,
    private val signer: EventSigner,
    private val eventPublisher: EventPublisherContract
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * [resumeIfNeeded] is called from both application start-up and the settings screen, and
     * [invoke] may overlap either of them. Pending-key state must only be inspected/mutated by
     * one of them at a time.
     */
    private val mutex = Mutex()

    /** Everything needed to publish and then locally apply the revocation for one group. */
    private class GroupRevocation(
        val group: Group,
        val updatedMembers: List<String>,
        val updatedCreatedBy: String,
        val updatedNames: Map<String, String>,
        val revocationEvent: NostrEvent,
        val revocationEncrypted: String,
        val metaEvent: NostrEvent,
        val metaEncrypted: String
    )

    /**
     * Revoke the current key and create a new identity.
     * Atomic: new key is stored alongside old key, only promoted after all publishes succeed.
     * On app startup, call [resumeIfNeeded] to complete any interrupted revocation.
     * @return the new public key hex
     */
    suspend operator fun invoke(): String {
        // NonCancellable: key revocation must complete atomically even if the
        // calling coroutine scope is cancelled (screen rotation, back press)
        return withContext(NonCancellable) {
            mutex.withLock { revoke() }
        }
    }

    private suspend fun revoke(): String {
        val oldPubkey = identity.getPublicKeyHex()
        val groups = groupRepo.getAll()

        // 1. Generate new keypair alongside old (does NOT overwrite) and stamp the start time
        //    straight away so resumeIfNeeded can recognise an interrupted attempt.
        val newPubkey = identity.generatePendingKeyPair()
        try {
            identity.markRevocationStarted()

            // 2. Build every payload and signed event up-front. Nothing has left the device yet,
            //    so a failure here (missing key, signer error) leaves no partial state behind.
            val skippedGroupIds = mutableListOf<String>()
            val plans = mutableListOf<GroupRevocation>()
            for (group in groups) {
                val groupKey = groupRepo.getGroupKey(group.id)
                if (groupKey == null) {
                    skippedGroupIds += group.id
                    continue
                }
                plans += buildRevocation(group, groupKey, oldPubkey, newPubkey)
            }
            if (skippedGroupIds.isNotEmpty()) {
                Log.w(
                    TAG,
                    "No current-epoch key for ${skippedGroupIds.size} group(s); revocation not published to: " +
                        skippedGroupIds.joinToString { it.take(8) }
                )
            }

            // 3. Publish revocations (signed by the OLD key), then the updated group_meta events.
            val revocationEventIds = mutableListOf<String>()
            for (plan in plans) {
                eventPublisher.publishDirect(
                    plan.revocationEvent,
                    plan.group.id,
                    plan.revocationEncrypted,
                    "key_revocation"
                )
                revocationEventIds += plan.revocationEvent.id
            }
            for (plan in plans) {
                eventPublisher.publishDirect(plan.metaEvent, plan.group.id, plan.metaEncrypted, "group_meta")
                revocationEventIds += plan.metaEvent.id
            }

            // 4. Every publish succeeded; only now swap old -> new in local membership. Until this
            //    point local state still names the OLD pubkey, so a failure above leaves a
            //    consistent (unrevoked) device.
            for (plan in plans) {
                groupRepo.updateFromMeta(
                    plan.group.id,
                    plan.group.name,
                    plan.updatedMembers,
                    plan.group.relays,
                    createdBy = plan.updatedCreatedBy,
                    memberNames = plan.updatedNames
                )
            }

            // 5. Store event IDs so resumeIfNeeded can track them
            identity.setRevocationEventIds(revocationEventIds)

            // 6. Promote the pending key
            identity.commitPendingKeyPair()
        } catch (e: Exception) {
            // Revocation failed: discard pending key, old key is still active
            identity.discardPendingKeyPair()
            throw e
        }

        Log.i(TAG, "Key revoked. Old: ${oldPubkey.take(8)}… New: ${newPubkey.take(8)}…")
        return newPubkey
    }

    private fun buildRevocation(group: Group, groupKey: String, oldPubkey: String, newPubkey: String): GroupRevocation {
        val revocationPayload =
            json.encodeToString(
                KeyRevocation.serializer(),
                KeyRevocation(oldPubkey = oldPubkey, newPubkey = newPubkey, reason = "Key compromised")
            )
        val revocationEncrypted = encryption.encrypt(revocationPayload, groupKey)
        val revocationEvent =
            signer.createSignedEvent(
                groupId = group.id,
                eventType = "key_revocation",
                encryptedContent = revocationEncrypted
            )

        val updatedMembers = group.members.map { if (it == oldPubkey) newPubkey else it }
        val updatedCreatedBy = if (group.createdBy == oldPubkey) newPubkey else group.createdBy
        val updatedNames = group.memberNames.toMutableMap().apply {
            remove(oldPubkey)?.let { put(newPubkey, it) }
        }
        val metaPayload =
            json.encodeToString(
                GroupMeta.serializer(),
                GroupMeta(
                    name = group.name,
                    description = group.description,
                    createdBy = updatedCreatedBy,
                    createdAt = group.createdAt,
                    members = updatedMembers,
                    relays = group.relays,
                    memberNames = updatedNames
                )
            )
        val metaEncrypted = encryption.encrypt(metaPayload, groupKey)
        val metaEvent =
            signer.createSignedEvent(
                groupId = group.id,
                eventType = "group_meta",
                encryptedContent = metaEncrypted
            )

        return GroupRevocation(
            group = group,
            updatedMembers = updatedMembers,
            updatedCreatedBy = updatedCreatedBy,
            updatedNames = updatedNames,
            revocationEvent = revocationEvent,
            revocationEncrypted = revocationEncrypted,
            metaEvent = metaEvent,
            metaEncrypted = metaEncrypted
        )
    }

    /**
     * Resume an incomplete revocation on app startup.
     *
     * - No recorded start time (pending key from an older build) or no recorded event IDs
     *   (crashed before anything was published): discard the pending key; the old key was
     *   never revoked anywhere.
     * - More than [MAX_REVOCATION_AGE_SECS] elapsed: commit anyway (timeout safety net).
     * - Tracked events still in the outbox: keep waiting.
     * - Otherwise the events have been published: commit.
     */
    suspend fun resumeIfNeeded() {
        mutex.withLock { resume() }
    }

    private suspend fun resume() {
        if (!identity.hasPendingKeyPair()) return
        val newPubkey = identity.getPendingPublicKeyHex() ?: return

        val eventIds = identity.getRevocationEventIds()
        val startTime = identity.getRevocationStartTime()
        val nowSecs = System.currentTimeMillis() / 1000

        if (startTime == 0L) {
            Log.w(TAG, "Pending key ${newPubkey.take(8)}… has no recorded revocation start, discarding")
            identity.discardPendingKeyPair()
            return
        }
        if (eventIds.isEmpty()) {
            Log.w(TAG, "Pending key ${newPubkey.take(8)}… was never published to any group, discarding")
            identity.discardPendingKeyPair()
            return
        }

        // Timeout: commit anyway after 24h to avoid permanent limbo
        if (nowSecs - startTime > MAX_REVOCATION_AGE_SECS) {
            Log.w(
                TAG,
                "Revocation timeout (${MAX_REVOCATION_AGE_SECS}s), committing pending key ${newPubkey.take(8)}…"
            )
            identity.commitPendingKeyPair()
            return
        }

        // Check if tracked revocation events are still in the outbox
        if (eventPublisher.hasOutboxEventsById(eventIds)) {
            Log.i(TAG, "Pending key ${newPubkey.take(8)}… waiting, revocation events still in outbox")
            return
        }

        Log.i(TAG, "Resuming incomplete key revocation → committing pending key ${newPubkey.take(8)}…")
        identity.commitPendingKeyPair()
    }

    /**
     * Handle an incoming key_revocation event from another member.
     * Replace their old pubkey with the new one in the group member list.
     */
    suspend fun handleRevocation(decryptedContent: String, authorPubkey: String, groupId: String) {
        val revocation =
            try {
                json.decodeFromString<KeyRevocation>(decryptedContent)
            } catch (e: Exception) {
                Log.w(TAG, "Invalid key_revocation payload: ${e.message}")
                return
            }

        // The event must be signed by the old key being revoked
        if (authorPubkey != revocation.oldPubkey) {
            Log.w(TAG, "key_revocation signed by $authorPubkey but claims to revoke ${revocation.oldPubkey}")
            return
        }

        val newPubkey = revocation.newPubkey
        if (newPubkey.isNotEmpty() && !PUBKEY_HEX.matches(newPubkey)) {
            Log.w(TAG, "key_revocation from ${authorPubkey.take(8)} carries a malformed new pubkey, rejecting")
            return
        }
        if (newPubkey == revocation.oldPubkey) {
            Log.w(TAG, "key_revocation from ${authorPubkey.take(8)} names itself as the new key, rejecting")
            return
        }

        // Remove revoked pubkey from local member list immediately
        val group = groupRepo.getById(groupId) ?: return
        if (revocation.oldPubkey !in group.members) return

        val replace = newPubkey.isNotEmpty() && newPubkey !in group.members
        val updated =
            if (replace) {
                // Replace old pubkey with new one (fallback if group_meta arrives late)
                group.members.map { if (it == revocation.oldPubkey) newPubkey else it }
            } else {
                // No new key, or the new key already joined: just drop the old one, never duplicate.
                group.members - revocation.oldPubkey
            }
        val updatedNames = group.memberNames.toMutableMap().apply {
            val oldName = remove(revocation.oldPubkey)
            // Carry the display name over, but never clobber a name the new key already announced.
            if (newPubkey.isNotEmpty() && oldName != null && newPubkey !in this) put(newPubkey, oldName)
        }
        groupRepo.updateFromMeta(
            groupId,
            group.name,
            updated,
            group.relays,
            memberNames = updatedNames
        )
        Log.i(
            TAG,
            "Processed key revocation ${revocation.oldPubkey.take(8)}… → ${newPubkey.take(8)}… in group $groupId"
        )
    }

    companion object {
        private const val TAG = "RevokeKeyUseCase"

        /** 24 hours: commit pending key even if outbox events haven't published */
        private const val MAX_REVOCATION_AGE_SECS = 24 * 60 * 60L

        /** A Nostr public key: exactly 64 lowercase hex characters. */
        private val PUBKEY_HEX = Regex("^[0-9a-f]{64}$")
    }
}
