package com.splitfree.domain.usecase.group

import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.invite.InviteLinkCodec
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
 * Creator-only relay update ordered by the signed metadata event's clock.
 *
 * Apply against the observed group before publishing. Connect to old and new relays so both can
 * receive the change, then request best-effort history repair when relays were added; this does
 * not guarantee every historical event reaches each new relay.
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
     * @param relays new relay URL list (non-empty and satisfying [InviteLinkCodec.fitsInviteLink])
     * @throws IllegalArgumentException if relays is empty or cannot fit in an invite link
     * @throws IllegalStateException if group or group key not found, or the metadata update is rejected
     */
    suspend operator fun invoke(groupId: String, relays: List<String>) {
        require(relays.isNotEmpty()) { "At least one relay is required" }
        require(InviteLinkCodec.fitsInviteLink(relays)) { "Relays do not fit in an invite link" }

        val group = groupRepo.getById(groupId) ?: error("Group not found")
        val groupKey = groupRepo.getGroupKeyForEpoch(group.id, group.keyEpoch) ?: error("Group key not found")
        require(group.createdBy == identity.getPublicKeyHex()) { "Only the group creator can change relays" }
        val oldRelays = group.relays.toSet()

        // Use the signed event clock locally too, rather than a separate wall-clock watermark.
        val meta = GroupMeta(
            name = group.name,
            description = group.description,
            createdBy = group.createdBy,
            createdAt = group.createdAt,
            members = group.members,
            relays = relays,
            memberNames = group.memberNames,
            keyEpoch = group.keyEpoch,
            originalCreator = group.originalCreator.takeIf {
                com.splitfree.domain.model.group.GroupIdentity.matches(group.id, it, group.createdAt)
            }.orEmpty(),
            creatorTransitions = group.creatorTransitions
        )
        val encrypted = encryption.encrypt(json.encodeToString(GroupMeta.serializer(), meta), groupKey)
        val event = signer.createSignedEvent(
            groupId = group.id,
            eventType = "group_meta",
            encryptedContent = encrypted
        )

        val updated = groupRepo.applyAuthenticatedMeta(
            groupId = group.id,
            meta = meta,
            author = event.pubkey,
            timestamp = event.createdAt,
            eventId = event.id,
            epoch = group.keyEpoch,
            expectedGroup = group
        )

        check(updated) { "Group changed before the relays could be saved. Try again" }

        // Keep old relays reachable for the announcement of their replacement.
        val allRelays = (oldRelays + relays).distinct()
        nostrClient.connect(allRelays)
        eventPublisher.publishDirect(event, group.id, encrypted, "group_meta")

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
