package com.splitfree.domain.usecase.group

import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupIdentity
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.SettingsContract
import com.splitfree.domain.util.RelayDefaults
import com.splitfree.util.DebugLog as Log
import javax.inject.Inject
import kotlinx.serialization.json.Json
/**
 * Creates a new expense group, generates its symmetric key, and publishes `group_meta` to relays.
 *
 * The group id is derived from `(creator, createdAt)` via [GroupIdentity] so that invite links
 * and imported metadata can prove who created the group.
 */
class CreateGroupUseCase
@Inject
constructor(
    private val groupRepo: GroupRepositoryContract,
    private val encryption: GroupEncryption,
    private val identity: IdentityContract,
    private val signer: EventSigner,
    private val eventPublisher: EventPublisherContract,
    private val settings: SettingsContract
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Create a new expense group and publish its `group_meta` to relays.
     *
     * @param name group display name (1–100 chars)
     * @param relays Nostr relay URLs for this group
     * @return the newly created [Group]
     * @throws IllegalArgumentException if [name] is blank or exceeds 100 chars
     */
    suspend operator fun invoke(name: String, relays: List<String> = RelayDefaults.DEFAULT_RELAYS): Group {
        require(name.isNotBlank() && name.length <= 100) { "Group name must be 1-100 characters" }
        val groupKey = encryption.generateGroupKey()
        val pubkey = identity.getPublicKeyHex()
        val myName = settings.displayName
        val names = if (myName.isNotBlank()) mapOf(pubkey to myName) else emptyMap()
        val createdAt = System.currentTimeMillis() / 1000
        val group =
            Group(
                id = GroupIdentity.derive(pubkey, createdAt),
                name = name,
                createdBy = pubkey,
                createdAt = createdAt,
                members = listOf(pubkey),
                relays = relays,
                memberNames = names
            )
        groupRepo.save(group, groupKey)
        Log.i(TAG, "Created group: ${group.id} name=$name creator=${pubkey.take(8)}")

        val meta = GroupMeta(
            name = group.name,
            description = group.description,
            createdBy = group.createdBy,
            createdAt = group.createdAt,
            members = group.members,
            relays = group.relays,
            memberNames = names
        )
        val metaJson = json.encodeToString(GroupMeta.serializer(), meta)
        val encrypted = encryption.encrypt(metaJson, groupKey)
        val event =
            signer.createSignedEvent(
                groupId = group.id,
                eventType = "group_meta",
                encryptedContent = encrypted
            )
        eventPublisher.publishDirect(event, group.id, encrypted, "group_meta")
        Log.i(TAG, "Published group_meta for ${group.id}")

        return group
    }

    companion object {
        private const val TAG = "CreateGroupUseCase"
    }
}
