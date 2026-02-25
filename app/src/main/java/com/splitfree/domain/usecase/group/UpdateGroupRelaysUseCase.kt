package com.splitfree.domain.usecase.group

import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.util.DebugLog as Log
import javax.inject.Inject
import kotlinx.serialization.json.Json

/**
 * Updates a group's relay list, persists locally, and publishes an updated `group_meta` event.
 */
class UpdateGroupRelaysUseCase
@Inject
constructor(
    private val groupRepo: GroupRepositoryContract,
    private val encryption: GroupEncryption,
    private val signer: EventSigner,
    private val eventPublisher: EventPublisherContract
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @param groupId target group UUID
     * @param relays new relay URL list (must be non-empty, all `wss://`)
     * @throws IllegalArgumentException if relays is empty or contains non-wss URLs
     * @throws IllegalStateException if group or group key not found
     */
    suspend operator fun invoke(groupId: String, relays: List<String>) {
        require(relays.isNotEmpty()) { "At least one relay is required" }
        require(relays.all { it.startsWith("wss://") }) { "Only wss:// relay URLs are allowed" }

        val group = groupRepo.getById(groupId) ?: error("Group not found")
        val groupKey = groupRepo.getGroupKey(groupId) ?: error("Group key not found")

        groupRepo.updateFromMeta(
            groupId = group.id,
            name = group.name,
            members = group.members,
            relays = relays,
            memberNames = group.memberNames
        )

        val meta = GroupMeta(
            name = group.name,
            description = group.description,
            createdBy = group.createdBy,
            createdAt = group.createdAt,
            members = group.members,
            relays = relays,
            memberNames = group.memberNames
        )
        val metaJson = json.encodeToString(GroupMeta.serializer(), meta)
        val encrypted = encryption.encrypt(metaJson, groupKey)
        val event = signer.createSignedEvent(
            groupId = group.id,
            eventType = "group_meta",
            encryptedContent = encrypted
        )
        eventPublisher.publishDirect(event, group.id, encrypted, "group_meta")
        Log.i(TAG, "Updated relays for group $groupId: ${relays.size} relays")
    }

    companion object {
        private const val TAG = "UpdateGroupRelays"
    }
}
