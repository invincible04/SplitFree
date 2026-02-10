package com.splitfree.domain.usecase

import com.splitfree.data.local.OutboxDao
import com.splitfree.data.local.entities.OutboxEntity
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.IdentityManager
import com.splitfree.domain.model.Group
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.UUID
import javax.inject.Inject

class CreateGroupUseCase @Inject constructor(
    private val groupRepo: GroupRepository,
    private val encryption: GroupEncryption,
    private val identity: IdentityManager,
    private val signer: EventSigner,
    private val outboxDao: OutboxDao
) {
    suspend operator fun invoke(name: String, relays: List<String> = DEFAULT_RELAYS): Group {
        val groupKey = encryption.generateGroupKey()
        val pubkey = identity.getPublicKey()
        val group = Group(
            id = UUID.randomUUID().toString(),
            name = name,
            createdBy = pubkey,
            createdAt = System.currentTimeMillis() / 1000,
            members = listOf(pubkey),
            relays = relays
        )
        groupRepo.save(group, groupKey)

        // Publish group_meta event so other members can discover it from relays
        val metaJson = buildJsonObject {
            put("name", group.name)
            put("description", group.description)
            put("created_by", group.createdBy)
            put("created_at", group.createdAt)
            putJsonArray("members") { group.members.forEach { add(it) } }
            putJsonArray("relays") { group.relays.forEach { add(it) } }
        }.toString()

        val encrypted = encryption.encrypt(metaJson, groupKey)
        val event = signer.createSignedEvent(
            groupId = group.id,
            eventType = "group_meta",
            encryptedContent = encrypted
        )
        outboxDao.insert(
            OutboxEntity(
                eventId = event.id().toHex(),
                eventJson = event.asJson(),
                createdAt = event.createdAt().asSecs().toLong()
            )
        )

        return group
    }

    companion object {
        val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.nostr.band",
            "wss://relay.snort.social",
            "wss://nostr.wine"
        )
    }
}
