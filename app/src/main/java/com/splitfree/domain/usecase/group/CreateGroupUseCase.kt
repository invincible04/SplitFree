package com.splitfree.domain.usecase.group

import com.splitfree.data.local.dao.OutboxDao
import com.splitfree.data.local.entities.OutboxEntity
import com.splitfree.data.nostr.EventThrottler
import com.splitfree.data.nostr.RelayConfig
import com.splitfree.data.nostr.relay.RelayConnectionManager
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.IdentityManager
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.util.DebugLog as Log
import java.util.UUID
import javax.inject.Inject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray

/**
 * Creates a new expense group, generates its symmetric key, and publishes `group_meta` to relays.
 */
class CreateGroupUseCase
@Inject
constructor(
    private val groupRepo: GroupRepositoryContract,
    private val encryption: GroupEncryption,
    private val identity: IdentityManager,
    private val signer: EventSigner,
    private val outboxDao: OutboxDao,
    private val throttler: EventThrottler,
    private val relayConnectionManager: RelayConnectionManager
) {
    /**
     * Create a new expense group and publish its `group_meta` to relays.
     *
     * @param name group display name (1–100 chars)
     * @param relays Nostr relay URLs for this group
     * @return the newly created [Group]
     * @throws IllegalArgumentException if [name] is blank or exceeds 100 chars
     */
    suspend operator fun invoke(name: String, relays: List<String> = RelayConfig.DEFAULT_RELAYS): Group {
        require(name.isNotBlank() && name.length <= 100) { "Group name must be 1-100 characters" }
        val groupKey = encryption.generateGroupKey()
        val pubkey = identity.getPublicKeyHex()
        val group =
            Group(
                id = UUID.randomUUID().toString(),
                name = name,
                createdBy = pubkey,
                createdAt = System.currentTimeMillis() / 1000,
                members = listOf(pubkey),
                relays = relays
            )
        groupRepo.save(group, groupKey)
        Log.i(TAG, "Created group: ${group.id} name=$name creator=${pubkey.take(8)}")

        // Publish group_meta event so other members can discover it from relays
        val metaJson =
            buildJsonObject {
                put("name", JsonPrimitive(group.name))
                put("description", JsonPrimitive(group.description))
                put("created_by", JsonPrimitive(group.createdBy))
                put("created_at", JsonPrimitive(group.createdAt))
                putJsonArray("members") { group.members.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("relays") { group.relays.forEach { add(JsonPrimitive(it)) } }
            }.toString()

        val encrypted = encryption.encrypt(metaJson, groupKey)
        val event =
            signer.createSignedEvent(
                groupId = group.id,
                eventType = "group_meta",
                encryptedContent = encrypted
            )
        outboxDao.insert(
            OutboxEntity(
                eventId = event.id,
                eventJson = event.toJson(),
                createdAt = event.createdAt
            )
        )
        // Publish immediately so the group is available when invite link is shared
        try {
            relayConnectionManager.ensureConnected()
        } catch (_: Exception) {
        }
        throttler.enqueue(event)
        Log.i(TAG, "Enqueued group_meta publish for ${group.id}")

        return group
    }

    companion object {
        private const val TAG = "CreateGroupUseCase"
    }
}
