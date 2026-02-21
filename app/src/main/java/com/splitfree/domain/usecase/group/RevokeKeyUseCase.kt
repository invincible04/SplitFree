package com.splitfree.domain.usecase.group

import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.model.group.KeyRevocation
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.util.DebugLog as Log
import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Key revocation: publish a signed "key_revocation" event to all groups,
 * then generate a new identity and re-join all groups with the new pubkey.
 *
 * Flow:
 * 1. Sign a revocation event with the OLD key (proves ownership)
 * 2. Generate new keypair
 * 3. For each group, publish a group_meta update adding the new pubkey and removing the old one
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
     * Revoke the current key and create a new identity.
     * Atomic: new key is stored alongside old key, only promoted after all publishes succeed.
     * On app startup, call [resumeIfNeeded] to complete any interrupted revocation.
     * @return the new public key hex
     */
    suspend operator fun invoke(): String {
        // NonCancellable: key revocation must complete atomically even if the
        // calling coroutine scope is cancelled (screen rotation, back press)
        return withContext(NonCancellable) {
            val oldPubkey = identity.getPublicKeyHex()
            val groups = groupRepo.getAll()

            // 1. Generate new keypair alongside old (does NOT overwrite)
            val (_, newPubkey) = identity.generatePendingKeyPair()
            val revocationEventIds = mutableListOf<String>()

            try {
                // 2. Publish revocation to each group with OLD key
                for (group in groups) {
                    val groupKey = groupRepo.getGroupKey(group.id) ?: continue
                    val payload =
                        json.encodeToString(
                            KeyRevocation.serializer(),
                            KeyRevocation(oldPubkey = oldPubkey, newPubkey = newPubkey, reason = "Key compromised")
                        )
                    val encrypted = encryption.encrypt(payload, groupKey)
                    val event =
                        signer.createSignedEvent(
                            groupId = group.id,
                            eventType = "key_revocation",
                            encryptedContent = encrypted
                        )
                    revocationEventIds += event.id
                    saveAndPublish(event, group.id, encrypted, payload, "key_revocation")
                }

                // 3. Publish updated group_meta with new pubkey (still signed by OLD key)
                for (group in groups) {
                    val groupKey = groupRepo.getGroupKey(group.id) ?: continue
                    val updatedMembers = group.members.map { if (it == oldPubkey) newPubkey else it }
                    val newCreatedBy = if (group.createdBy == oldPubkey) newPubkey else group.createdBy
                    val updatedNames = group.memberNames.toMutableMap().apply {
                        remove(oldPubkey)?.let { put(newPubkey, it) }
                    }
                    groupRepo.updateFromMeta(
                        group.id,
                        group.name,
                        updatedMembers,
                        group.relays,
                        createdBy = newCreatedBy,
                        memberNames = updatedNames
                    )

                    val metaPayload =
                        json.encodeToString(
                            GroupMeta.serializer(),
                            GroupMeta(
                                name = group.name,
                                description = group.description,
                                createdBy = if (group.createdBy == oldPubkey) newPubkey else group.createdBy,
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
                    revocationEventIds += metaEvent.id
                    saveAndPublish(metaEvent, group.id, metaEncrypted, metaPayload, "group_meta")
                }

                // 4. Store event IDs so resumeIfNeeded can track them
                identity.setRevocationEventIds(revocationEventIds)

                // 5. All publishes succeeded — now promote the pending key
                identity.commitPendingKeyPair()
            } catch (e: Exception) {
                // Revocation failed — discard pending key, old key is still active
                identity.discardPendingKeyPair()
                throw e
            }

            Log.i(TAG, "Key revoked. Old: ${oldPubkey.take(8)}… New: ${newPubkey.take(8)}…")
            newPubkey
        }
    }

    /**
     * Resume an incomplete revocation on app startup.
     * Commits the pending key if:
     * - the tracked revocation events have left the outbox (published), OR
     * - more than [MAX_REVOCATION_AGE_SECS] have elapsed (timeout safety net)
     */
    suspend fun resumeIfNeeded() {
        if (!identity.hasPendingKeyPair()) return
        val newPubkey = identity.getPendingPublicKeyHex() ?: return

        val eventIds = identity.getRevocationEventIds()
        val startTime = identity.getRevocationStartTime()
        val nowSecs = System.currentTimeMillis() / 1000

        // Timeout: commit anyway after 24h to avoid permanent limbo
        if (startTime > 0 && nowSecs - startTime > MAX_REVOCATION_AGE_SECS) {
            Log.w(
                TAG,
                "Revocation timeout (${MAX_REVOCATION_AGE_SECS}s) — committing pending key ${newPubkey.take(8)}…"
            )
            identity.commitPendingKeyPair()
            return
        }

        // Check if tracked revocation events are still in the outbox
        if (eventIds.isNotEmpty() && eventPublisher.hasOutboxEventsById(eventIds)) {
            Log.i(TAG, "Pending key ${newPubkey.take(8)}… waiting — revocation events still in outbox")
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

        // Remove revoked pubkey from local member list immediately
        val group = groupRepo.getById(groupId) ?: return
        if (revocation.oldPubkey in group.members) {
            val updated =
                if (revocation.newPubkey.isNotEmpty()) {
                    // Replace old pubkey with new one (fallback if group_meta arrives late)
                    group.members.map { if (it == revocation.oldPubkey) revocation.newPubkey else it }
                } else {
                    group.members - revocation.oldPubkey
                }
            val updatedNames = group.memberNames.toMutableMap().apply {
                if (revocation.newPubkey.isNotEmpty()) {
                    remove(revocation.oldPubkey)?.let { put(revocation.newPubkey, it) }
                } else {
                    remove(revocation.oldPubkey)
                }
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
                "Processed key revocation ${revocation.oldPubkey.take(
                    8
                )}… → ${revocation.newPubkey.take(8)}… in group $groupId"
            )
        }
    }

    private suspend fun saveAndPublish(
        event: NostrEvent,
        groupId: String,
        encrypted: String,
        plaintext: String,
        eventType: String
    ) {
        eventPublisher.publishDirect(event, groupId, encrypted, eventType)
    }

    companion object {
        private const val TAG = "RevokeKeyUseCase"

        /** 24 hours — commit pending key even if outbox events haven't published */
        private const val MAX_REVOCATION_AGE_SECS = 24 * 60 * 60L
    }
}
