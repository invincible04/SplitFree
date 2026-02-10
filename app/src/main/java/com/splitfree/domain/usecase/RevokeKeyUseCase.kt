package com.splitfree.domain.usecase

import android.util.Log
import com.splitfree.data.local.EventDao
import com.splitfree.data.local.OutboxDao
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.data.local.entities.OutboxEntity
import com.splitfree.data.nostr.EventThrottler
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.IdentityManager
import com.splitfree.domain.crypto.NostrEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject

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
class RevokeKeyUseCase @Inject constructor(
    private val identity: IdentityManager,
    private val groupRepo: GroupRepository,
    private val encryption: GroupEncryption,
    private val signer: EventSigner,
    private val eventDao: EventDao,
    private val outboxDao: OutboxDao,
    private val throttler: EventThrottler
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Revoke the current key and create a new identity.
     * @return the new public key hex
     */
    suspend operator fun invoke(): String {
        val oldPubkey = identity.getPublicKeyHex()
        val groups = groupRepo.getAll()

        // 1. Publish revocation to each group with OLD key
        for (group in groups) {
            val groupKey = groupRepo.getGroupKey(group.id) ?: continue
            val payload = json.encodeToString(
                KeyRevocation.serializer(),
                KeyRevocation(oldPubkey = oldPubkey, reason = "Key compromised")
            )
            val encrypted = encryption.encrypt(payload, groupKey)
            val event = signer.createSignedEvent(
                groupId = group.id,
                eventType = "key_revocation",
                encryptedContent = encrypted
            )
            saveAndPublish(event, group.id, encrypted, payload, "key_revocation")
        }

        // 2. Generate new keypair (overwrites old in EncryptedSharedPreferences)
        val (_, newPubkey) = identity.generateKeyPair()

        // 3. Re-join each group: publish updated group_meta with new pubkey
        for (group in groups) {
            val groupKey = groupRepo.getGroupKey(group.id) ?: continue
            val updatedMembers = group.members.map { if (it == oldPubkey) newPubkey else it }
            groupRepo.updateFromMeta(group.id, group.name, updatedMembers, group.relays)

            val metaPayload = json.encodeToString(
                com.splitfree.domain.model.GroupMeta.serializer(),
                com.splitfree.domain.model.GroupMeta(
                    name = group.name,
                    description = group.description,
                    createdBy = if (group.createdBy == oldPubkey) newPubkey else group.createdBy,
                    createdAt = group.createdAt,
                    members = updatedMembers,
                    relays = group.relays
                )
            )
            val metaEncrypted = encryption.encrypt(metaPayload, groupKey)
            val metaEvent = signer.createSignedEvent(
                groupId = group.id,
                eventType = "group_meta",
                encryptedContent = metaEncrypted
            )
            saveAndPublish(metaEvent, group.id, metaEncrypted, metaPayload, "group_meta")
        }

        Log.i(TAG, "Key revoked. Old: ${oldPubkey.take(8)}… New: ${newPubkey.take(8)}…")
        return newPubkey
    }

    /**
     * Handle an incoming key_revocation event from another member.
     * Replace their old pubkey with the new one in the group member list.
     */
    suspend fun handleRevocation(decryptedContent: String, authorPubkey: String, groupId: String) {
        val revocation = try {
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
            val updated = group.members - revocation.oldPubkey
            groupRepo.updateFromMeta(groupId, group.name, updated, group.relays)
            Log.i(TAG, "Removed revoked key ${revocation.oldPubkey.take(8)}… from group $groupId")
        }
    }

    private suspend fun saveAndPublish(
        event: NostrEvent,
        groupId: String,
        encrypted: String,
        plaintext: String,
        eventType: String
    ) {
        eventDao.insert(
            EventEntity(
                eventId = event.id, groupId = groupId,
                pubkey = event.pubkey, createdAt = event.createdAt,
                kind = 30078, contentEncrypted = encrypted,
                contentDecrypted = plaintext, eventType = eventType,
                expenseUuid = null, sig = event.sig,
                receivedAt = System.currentTimeMillis() / 1000,
                originalEventJson = event.toJson()
            )
        )
        outboxDao.insert(OutboxEntity(eventId = event.id, eventJson = event.toJson(), createdAt = event.createdAt))
        throttler.enqueue(event)
    }

    companion object {
        private const val TAG = "RevokeKeyUseCase"
    }
}

@Serializable
data class KeyRevocation(
    val oldPubkey: String,
    val reason: String = ""
)
