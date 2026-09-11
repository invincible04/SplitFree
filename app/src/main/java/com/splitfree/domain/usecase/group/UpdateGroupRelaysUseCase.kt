package com.splitfree.domain.usecase.group

import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.NostrClientContract
import com.splitfree.domain.usecase.sync.SelfHealUseCase
import com.splitfree.util.DebugLog as Log
import javax.inject.Inject
import kotlinx.serialization.json.Json

/**
 * Updates a group's relay list, persists locally, publishes an updated `group_meta` event,
 * and re-publishes existing events to the new relays so history is not lost.
 *
 * Flow:
 * 1. Validate relay URLs (non-empty, all `wss://`)
 * 2. Update local group record with new relays
 * 3. Reconnect [NostrClientContract] to include the new relays
 * 4. Encrypt and publish `group_meta` event with updated relay list
 * 5. Run [SelfHealUseCase] to re-publish any local events missing from the new relays
 */
class UpdateGroupRelaysUseCase
@Inject
constructor(
    private val groupRepo: GroupRepositoryContract,
    private val encryption: GroupEncryption,
    private val signer: EventSigner,
    private val eventPublisher: EventPublisherContract,
    private val nostrClient: NostrClientContract,
    private val selfHeal: SelfHealUseCase,
    private val identity: IdentityContract
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
        val groupKey = groupRepo.getGroupKeyForEpoch(group.id, group.keyEpoch) ?: error("Group key not found")
        require(group.createdBy == identity.getPublicKeyHex()) { "Only the group creator can change relays" }
        val oldRelays = group.relays.toSet()

        // 1. Update local
        groupRepo.updateFromMeta(
            groupId = group.id,
            name = group.name,
            members = group.members,
            relays = relays,
            memberNames = group.memberNames
        )

        // 2. Reconnect to include new relays
        val allRelays = (oldRelays + relays).distinct()
        nostrClient.connect(allRelays)

        // 3. Publish group_meta
        val meta = GroupMeta(
            name = group.name,
            description = group.description,
            createdBy = group.createdBy,
            createdAt = group.createdAt,
            members = group.members,
            relays = relays,
            memberNames = group.memberNames
        )
        val encrypted = encryption.encrypt(json.encodeToString(GroupMeta.serializer(), meta), groupKey)
        val event = signer.createSignedEvent(
            groupId = group.id,
            eventType = "group_meta",
            encryptedContent = encrypted
        )
        eventPublisher.publishDirect(event, group.id, encrypted, "group_meta")

        // 4. Re-publish existing events to new relays
        val newRelays = relays.toSet() - oldRelays
        if (newRelays.isNotEmpty()) {
            val healed = selfHeal(groupId)
            Log.i(TAG, "Re-published $healed events to new relays for group $groupId")
        }

        Log.i(TAG, "Updated relays for group $groupId: ${relays.size} relays")
    }

    companion object {
        private const val TAG = "UpdateGroupRelays"
    }
}
