package com.splitfree.domain.usecase

import com.splitfree.data.local.OutboxDao
import com.splitfree.data.local.entities.OutboxEntity
import com.splitfree.data.nostr.EventThrottler
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.IdentityManager
import com.splitfree.domain.model.Group
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import java.util.UUID
import javax.inject.Inject

class CreateGroupUseCase
    @Inject
    constructor(
        private val groupRepo: GroupRepository,
        private val encryption: GroupEncryption,
        private val identity: IdentityManager,
        private val signer: EventSigner,
        private val outboxDao: OutboxDao,
        private val throttler: EventThrottler,
    ) {
        suspend operator fun invoke(
            name: String,
            relays: List<String> = DEFAULT_RELAYS,
        ): Group {
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
                    relays = relays,
                )
            groupRepo.save(group, groupKey)

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
                    encryptedContent = encrypted,
                )
            outboxDao.insert(
                OutboxEntity(
                    eventId = event.id,
                    eventJson = event.toJson(),
                    createdAt = event.createdAt,
                ),
            )
            // Publish immediately so the group is available when invite link is shared
            throttler.enqueue(event)

            return group
        }

        companion object {
            val DEFAULT_RELAYS =
                listOf(
                    "wss://relay.damus.io",
                    "wss://nos.lol",
                    "wss://relay.nostr.band",
                    "wss://relay.snort.social",
                    "wss://nostr.wine",
                )
            const val MAX_GROUP_MEMBERS = 50
        }
    }
