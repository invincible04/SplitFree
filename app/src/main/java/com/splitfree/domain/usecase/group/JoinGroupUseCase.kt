package com.splitfree.domain.usecase.group

import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.NostrClientContract
import com.splitfree.domain.repository.SyncEngineContract
import com.splitfree.domain.usecase.sync.SelfHealUseCase
import com.splitfree.util.DebugLog as Log
import javax.inject.Inject
import kotlinx.serialization.json.Json

/**
 * Parses an invite link, retrieves the group key, syncs existing events, and joins the group.
 */
class JoinGroupUseCase
@Inject
constructor(
    private val groupRepo: GroupRepositoryContract,
    private val identity: IdentityContract,
    private val nostrClient: NostrClientContract,
    private val signer: EventSigner,
    private val encryption: GroupEncryption,
    private val eventPublisher: EventPublisherContract,
    private val selfHeal: SelfHealUseCase,
    private val syncEngine: SyncEngineContract
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parse an invite link and join the group.
     *
     * Decodes the compact link, retrieves the group key via ephemeral key exchange,
     * saves the group locally, performs initial sync, and publishes a join announcement.
     *
     * @param uri `splitfree://join?d=<compact_base64_payload>` deep link
     * @return the joined [Group]
     * @throws IllegalArgumentException if the link is malformed or the group ID is invalid
     * @throws IllegalStateException if the link is expired, relays are empty, or key retrieval fails
     */
    suspend operator fun invoke(uri: String): Group {
        Log.i(TAG, "Joining via link: ${uri.take(80)}...")
        val invite = InviteLinkCodec.decode(uri)

        // Validate group ID is a valid UUID
        try {
            java.util.UUID.fromString(invite.groupId)
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid group ID format")
        }

        check(invite.relays.isNotEmpty()) { "Invite link must contain at least one relay" }

        // Validate invite link expiration
        if (System.currentTimeMillis() / 1000 > invite.expiry) {
            throw IllegalStateException("This invite link has expired. Ask the group creator for a new one.")
        }

        val groupKey = invite.groupKey

        Log.i(TAG, "Parsed invite: group=${invite.groupId} name=${invite.name} relays=${invite.relays.size}")

        val existing = groupRepo.getById(invite.groupId)
        if (existing != null) {
            Log.i(TAG, "Already in group ${invite.groupId}")
            return existing
        }

        val pubkey = identity.getPublicKeyHex()
        val group =
            Group(
                id = invite.groupId,
                name = invite.name,
                createdBy = "",
                createdAt = System.currentTimeMillis() / 1000,
                members = listOf(pubkey),
                relays = invite.relays
            )
        groupRepo.save(group, groupKey)

        // Connect, sync existing events, publish our join, then release
        try {
            ensureConnected(group.relays)
            initialSync(group, groupKey)
            selfHeal(group.id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Initial sync failed: ${e.message}")
        }

        // After initial sync, publish a group_meta that includes ourselves.
        // This announces our join to other members via relays.
        val currentGroup = groupRepo.getById(group.id) ?: group
        val updatedMembers =
            if (pubkey in currentGroup.members) {
                currentGroup.members
            } else {
                currentGroup.members + pubkey
            }
        groupRepo.updateFromMeta(group.id, currentGroup.name, updatedMembers, currentGroup.relays)
        Log.i(TAG, "Local members after join: ${updatedMembers.map { it.take(8) }}")

        // Publish join announcement while still connected
        publishGroupMeta(
            group.id,
            currentGroup.name,
            currentGroup.createdBy.ifEmpty {
                pubkey
            },
            currentGroup.createdAt,
            updatedMembers,
            currentGroup.relays,
            groupKey
        )

        return groupRepo.getById(group.id) ?: group
    }

    /** Connects to the given relays if not already connected, setting up auth signing. */
    private suspend fun ensureConnected(relays: List<String>) {
        if (!nostrClient.isConnected) {
            nostrClient.authSigner = { challenge, relayUrl -> signer.createAuthEvent(challenge, relayUrl) }
            nostrClient.connect(relays)
            Log.i(TAG, "Connected to ${relays.size} relays for join")
        }
    }

    /**
     * Pull all existing events for a newly joined group from relays.
     */
    private suspend fun initialSync(group: Group, groupKey: String) {
        val count = syncEngine.pullEvents(group.id, 0, groupKey, lenientTimestamp = true)
        if (count > 0) {
            Log.i(TAG, "Initial sync stored $count events for group ${group.name}")
        } else {
            Log.i(TAG, "Initial sync: no new events found")
        }
    }

    /** Publishes an encrypted group_meta event announcing the updated member list to relays. */
    private suspend fun publishGroupMeta(
        groupId: String,
        name: String,
        createdBy: String,
        createdAt: Long,
        members: List<String>,
        relays: List<String>,
        groupKey: String
    ) {
        try {
            val meta = GroupMeta(
                name = name,
                description = "",
                createdBy = createdBy,
                createdAt = createdAt,
                members = members,
                relays = relays
            )
            val metaJson = json.encodeToString(GroupMeta.serializer(), meta)
            val encrypted = encryption.encrypt(metaJson, groupKey)
            val event =
                signer.createSignedEvent(
                    groupId = groupId,
                    eventType = "group_meta",
                    encryptedContent = encrypted
                )
            eventPublisher.publishDirect(event, groupId, encrypted, "group_meta")
            Log.i(TAG, "Published group_meta with ${members.size} members for group $groupId")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to publish group_meta on join: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "JoinGroupUseCase"
    }
}
