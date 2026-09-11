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
import com.splitfree.domain.repository.SettingsContract
import com.splitfree.domain.repository.SyncEngineContract
import com.splitfree.domain.usecase.sync.SelfHealUseCase
import com.splitfree.util.DebugLog as Log
import javax.inject.Inject
import kotlinx.serialization.json.Json

/**
 * Parses an invite link, stores the group and its epoch key, syncs existing events, and joins the group.
 *
 * The link's creator claim is trusted only because [InviteLinkCodec.decode] has already verified
 * that the group id is derived from `(creatorPubkey, createdAt)`; the inviter is irrelevant.
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
    private val syncEngine: SyncEngineContract,
    private val settings: SettingsContract
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parse an invite link and join the group.
     *
     * Decodes the compact link, saves the group locally with the creator, creation time and key
     * epoch carried by the link (so the key lands under `groupId:epoch` and the next `key_rotation`
     * is accepted), performs initial sync, and publishes a join announcement.
     *
     * @param uri `splitfree://join?d=<compact_base64_payload>` deep link
     * @return the joined [Group]
     * @throws IllegalArgumentException if the link is malformed, expired, or its creator claim does not
     *   match the group id
     * @throws IllegalStateException if the link carries no relays
     */
    suspend operator fun invoke(uri: String): Group {
        // Never log the link itself: the payload is a bearer credential carrying the group key.
        Log.i(TAG, "Joining via link")
        val invite = InviteLinkCodec.decode(uri)

        // Validate group ID is a valid UUID
        try {
            java.util.UUID.fromString(invite.groupId)
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid group ID format")
        }

        check(invite.relays.isNotEmpty()) { "Invite link must contain at least one relay" }

        val groupKey = invite.groupKey

        Log.i(
            TAG,
            "Parsed invite: group=${invite.groupId} name=${invite.name} relays=${invite.relays.size} " +
                "creator=${invite.creatorPubkey.take(8)} epoch=${invite.keyEpoch}"
        )

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
                createdBy = invite.creatorPubkey,
                createdAt = invite.createdAt,
                members = listOf(pubkey),
                relays = invite.relays,
                keyEpoch = invite.keyEpoch
            )
        // Stores the key under "<groupId>:<keyEpoch>" (and the legacy plain id only for epoch 0).
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
        if (currentGroup.createdBy.isEmpty() || currentGroup.createdBy != invite.creatorPubkey) {
            // The id is bound to the invite's creator, so nothing pulled during sync should have
            // changed this; if it did, a trusted-creator hand-over path is misbehaving.
            Log.w(
                TAG,
                "Creator mismatch after initial sync for ${group.id}: " +
                    "local=${currentGroup.createdBy.take(8)} invite=${invite.creatorPubkey.take(8)}"
            )
        }
        val updatedMembers =
            if (pubkey in currentGroup.members) {
                currentGroup.members
            } else {
                currentGroup.members + pubkey
            }
        val myName = settings.displayName
        val updatedNames = currentGroup.memberNames.toMutableMap()
        if (myName.isNotBlank()) updatedNames[pubkey] = myName
        groupRepo.updateFromMeta(
            group.id,
            currentGroup.name,
            updatedMembers,
            currentGroup.relays,
            memberNames = updatedNames
        )
        Log.i(TAG, "Local members after join: ${updatedMembers.map { it.take(8) }}")

        // Publish join announcement while still connected
        publishGroupMeta(
            group.id,
            currentGroup.name,
            currentGroup.createdBy,
            currentGroup.createdAt,
            updatedMembers,
            currentGroup.relays,
            groupKey,
            updatedNames
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
        groupKey: String,
        memberNames: Map<String, String> = emptyMap()
    ) {
        try {
            val meta = GroupMeta(
                name = name,
                description = "",
                createdBy = createdBy,
                createdAt = createdAt,
                members = members,
                relays = relays,
                memberNames = memberNames
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
