package com.splitfree.domain.usecase.group

import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.SettingsContract
import com.splitfree.util.DebugLog as Log
import javax.inject.Inject
import kotlinx.serialization.json.Json

/**
 * Broadcasts the user's updated display name to all groups via `group_meta` events.
 *
 * When a user changes their name in settings, this use case updates the local
 * `memberNames` for every group and publishes the change to relays so other
 * members see the new name.
 */
class UpdateDisplayNameUseCase
@Inject
constructor(
    private val groupRepo: GroupRepositoryContract,
    private val identity: IdentityContract,
    private val encryption: GroupEncryption,
    private val signer: EventSigner,
    private val eventPublisher: EventPublisherContract,
    private val settings: SettingsContract
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Persist the new display name locally and broadcast to all groups.
     *
     * @param name the new display name (already trimmed/capped by caller)
     */
    suspend operator fun invoke(name: String) {
        settings.displayName = name
        val pubkey = identity.getPublicKeyHex()
        val groups = groupRepo.getAll()

        for (group in groups) {
            try {
                // An explicit empty value clears the name on every receiver: the member path leaves an
                // absent entry alone, and the creator path drops empties when sanitising.
                val updatedNames = group.memberNames.toMutableMap()
                updatedNames[pubkey] = name.trim()

                val groupKey = groupRepo.getGroupKeyForEpoch(group.id, group.keyEpoch) ?: continue
                val meta = GroupMeta(
                    name = group.name,
                    description = group.description,
                    createdBy = group.createdBy,
                    createdAt = group.createdAt,
                    members = group.members,
                    relays = group.relays,
                    memberNames = updatedNames
                )
                val metaJson = json.encodeToString(GroupMeta.serializer(), meta)
                val encrypted = encryption.encrypt(metaJson, groupKey)
                val event = signer.createSignedEvent(
                    groupId = group.id,
                    eventType = "group_meta",
                    encryptedContent = encrypted
                )
                // My own name is ordered by my own clock, never by the creator's watermark: renaming
                // myself must not block a creator meta that is still in flight.
                groupRepo.applyMemberSelfUpdate(
                    group.id,
                    pubkey,
                    event.createdAt,
                    event.id,
                    join = false,
                    displayName = name.trim()
                )
                eventPublisher.publishDirect(event, group.id, encrypted, "group_meta")
                Log.i(TAG, "Broadcast name update to group ${group.id}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to broadcast name to group ${group.id}: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "UpdateDisplayName"
    }
}
