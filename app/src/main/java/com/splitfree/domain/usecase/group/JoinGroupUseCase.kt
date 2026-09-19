package com.splitfree.domain.usecase.group

import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.EventRepositoryContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.IdentityState
import com.splitfree.domain.repository.NostrClientContract
import com.splitfree.domain.repository.SettingsContract
import com.splitfree.domain.repository.SyncEngineContract
import com.splitfree.domain.usecase.sync.SelfHealUseCase
import com.splitfree.util.DebugLog as Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Parses an invite link, stores the group and its epoch key, syncs existing events, and joins the group.
 *
 * [InviteLinkCodec.decode] verifies the original creator binding and any succession certificates;
 * the inviter supplies the bearer key, not creator authority.
 *
 * The group and key are saved before network sync. A retry resumes a saved group with no stored
 * self-authored `group_meta`; an existing creator or stored announcement takes the already-joined path.
 * Local membership alone is not treated as a completed announcement.
 */
@Singleton
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
    private val settings: SettingsContract,
    private val eventRepo: EventRepositoryContract
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val joinLock = Mutex()

    /**
     * Parse an invite link and join the group.
     *
     * Save the invite's root evidence and advertised epoch key before best-effort initial sync,
     * then announce using the post-sync group state.
     *
     * @param uri `splitfree://join?d=<compact_base64_payload>` deep link
     * @return the joined [Group]
     * @throws IllegalArgumentException if the link is malformed, expired, or its creator claim does not
     *   match the group id
     * @throws IllegalStateException if the link carries no relays
     */
    suspend operator fun invoke(uri: String): Group = joinLock.withLock { join(uri) }

    private suspend fun join(uri: String): Group {
        // Never log the link itself: the payload is a bearer credential carrying the group key.
        Log.i(TAG, "Joining via link")
        val invite = InviteLinkCodec.decode(uri)

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

        val pubkey = readyPublicKey()
        val existing = groupRepo.getById(invite.groupId)
        check(readyPublicKey() == pubkey) { "Identity changed while joining" }
        val offeredGroup = existing ?: Group(
            id = invite.groupId,
            name = invite.name,
            createdBy = invite.creatorPubkey,
            createdAt = invite.createdAt,
            members = listOf(pubkey),
            relays = invite.relays,
            keyEpoch = invite.keyEpoch,
            creatorTransitions = invite.creatorTransitions
        )
        val group = eventPublisher.prepareJoinedGroup(offeredGroup, groupKey, pubkey)
        if (invite.creatorTransitions.isNotEmpty()) {
            check(
                groupRepo.mergeCreatorBootstrap(
                    group.id,
                    invite.creatorPubkey,
                    invite.createdAt,
                    invite.creatorTransitions
                )
            ) { "Creator authority could not be restored" }
        }
        if (group.createdBy == pubkey || hasAnnouncedJoin(group.id, pubkey)) {
            Log.i(TAG, "Already in group ${invite.groupId}")
            val joined = groupRepo.getById(group.id) ?: group
            check(readyPublicKey() == pubkey) { "Identity changed while joining" }
            return joined
        }

        val activeKey = groupRepo.getGroupKeyForEpoch(group.id, group.keyEpoch)?.takeIf { it.isNotEmpty() } ?: run {
            check(invite.keyEpoch == group.keyEpoch) { "Invite does not contain the current group key" }
            check(readyPublicKey() == pubkey) { "Identity changed while joining" }
            groupRepo.saveGroupKeyForEpoch(group.id, group.keyEpoch, groupKey)
            check(requireEpochKey(group) == groupKey) { "Group key could not be restored" }
            groupKey
        }

        // Sync is best effort; relay failures must not prevent preparing the local join announcement.
        try {
            ensureConnected(group.relays)
            initialSync(group, activeKey)
            selfHeal(group.id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Initial sync failed: ${e.message}")
        }

        // Sync may change current authority, but never the original group identity.
        check(readyPublicKey() == pubkey) { "Identity changed while joining" }
        val currentGroup = checkNotNull(groupRepo.getById(group.id)) { "Group removed while joining" }
        check(currentGroup.originalCreator == invite.creatorPubkey && currentGroup.createdAt == invite.createdAt) {
            "Original group identity changed while joining"
        }
        val updatedMembers =
            if (pubkey in currentGroup.members) {
                currentGroup.members
            } else {
                currentGroup.members + pubkey
            }
        val myName = settings.displayNameFor(pubkey)
        val updatedNames = currentGroup.memberNames.toMutableMap()
        if (myName.isNotBlank()) updatedNames[pubkey] = myName

        // Sign above our name clock and apply that same event clock locally. A self-join must not
        // advance the creator's metadata clock and suppress creator updates still in flight.
        val now = System.currentTimeMillis() / 1000
        val floor = groupRepo.nameClockFloor(group.id, pubkey)
        check(floor < now + 3600) { "Join clock is ahead of the allowed window. Try again later" }
        val timestamp = maxOf(now, floor + 1)
        val announcementKey = requireEpochKey(currentGroup)
        val joinEvent =
            buildGroupMeta(
                group.id,
                currentGroup.name,
                currentGroup.createdBy,
                currentGroup.createdAt,
                updatedMembers,
                currentGroup.relays,
                announcementKey,
                updatedNames,
                currentGroup.keyEpoch,
                timestamp,
                currentGroup.originalCreator,
                currentGroup.creatorTransitions
            )
        check(readyPublicKey() == pubkey && joinEvent.event.pubkey == pubkey) { "Identity changed while joining" }
        check(eventPublisher.publishJoinedGroup(joinEvent.event, currentGroup, joinEvent.meta)) {
            "Group changed while joining. Try again"
        }

        return groupRepo.getById(group.id) ?: group
    }

    private suspend fun requireEpochKey(group: Group): String =
        checkNotNull(groupRepo.getGroupKeyForEpoch(group.id, group.keyEpoch)?.takeIf { it.isNotEmpty() }) {
            "Current group key unavailable"
        }

    private class SignedMeta(val event: NostrEvent, val meta: GroupMeta)

    /** A stored self-authored meta marks a prior announcement; this does not check relay delivery. */
    private suspend fun hasAnnouncedJoin(groupId: String, pubkey: String): Boolean =
        eventRepo.getEventsByType(groupId, "group_meta").any { it.pubkey == pubkey }

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
        val stored = syncEngine.pullEvents(group.id, 0, groupKey, lenientTimestamp = true).stored
        if (stored > 0) {
            Log.i(TAG, "Initial sync stored $stored events for group ${group.name}")
        } else {
            Log.i(TAG, "Initial sync: no new events found")
        }
    }

    /** Builds an encrypted, signed group_meta announcing the updated member list. */
    private fun buildGroupMeta(
        groupId: String,
        name: String,
        createdBy: String,
        createdAt: Long,
        members: List<String>,
        relays: List<String>,
        groupKey: String,
        memberNames: Map<String, String>,
        keyEpoch: Int,
        eventTimestamp: Long,
        originalCreator: String,
        creatorTransitions: List<com.splitfree.domain.model.group.CreatorTransition>
    ): SignedMeta {
        val meta = GroupMeta(
            name = name,
            description = "",
            createdBy = createdBy,
            createdAt = createdAt,
            members = members,
            relays = relays,
            memberNames = memberNames,
            keyEpoch = keyEpoch,
            originalCreator = originalCreator,
            creatorTransitions = creatorTransitions
        )
        val metaJson = json.encodeToString(GroupMeta.serializer(), meta)
        val encrypted = encryption.encrypt(metaJson, groupKey)
        val event =
            signer.createSignedEvent(
                groupId = groupId,
                eventType = "group_meta",
                encryptedContent = encrypted,
                createdAt = eventTimestamp
            )
        return SignedMeta(event, meta)
    }

    private fun readyPublicKey(): String {
        check(identity.identityState() == IdentityState.READY) { "Finish identity setup before joining" }
        return identity.getPublicKeyHex().also {
            check(PUBLIC_KEY.matches(it)) { "A valid identity is required before joining" }
        }
    }

    companion object {
        private const val TAG = "JoinGroupUseCase"
        private val PUBLIC_KEY = Regex("[0-9a-f]{64}")
    }
}
