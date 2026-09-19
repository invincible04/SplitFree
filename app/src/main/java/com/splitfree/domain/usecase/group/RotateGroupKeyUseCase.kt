package com.splitfree.domain.usecase.group

import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.nip.Nip44
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.model.group.KeyRotation
import com.splitfree.domain.repository.ControlOperation
import com.splitfree.domain.repository.ControlOperationJournalContract
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.EventRepositoryContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.util.hexToBytes
import com.splitfree.sync.event.RotationOutcome
import com.splitfree.util.DebugLog as Log
import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Creator intents and signed envelopes are persisted before publication; retries never retarget an epoch.
 *
 * Removal follows the person: a successor the removed key revoked itself to while the removal was pending
 * leaves with it and never receives the new epoch key, unless it was already an independent member when
 * the removal was decided. The payload still names the key the creator selected.
 *
 * Recovery keeps the selected member and target epoch fixed:
 * - Unprepared intents rebase onto the live roster, reusing any stored target-epoch key.
 * - Prepared envelopes are immutable. Missing recipients get envelopes for the same key, and
 *   roster drift appends metadata newer than every prepared meta, even if the roster changed back.
 *
 * Recovery rejects changed creator authority, conflicting epoch/key state or failed projection.
 * The journal remains until publication and local projection complete.
 */
class RotateGroupKeyUseCase
@Inject
constructor(
    private val groupRepo: GroupRepositoryContract,
    private val encryption: GroupEncryption,
    private val identity: IdentityContract,
    private val signer: EventSigner,
    private val eventPublisher: EventPublisherContract,
    private val eventRepo: EventRepositoryContract,
    private val journal: ControlOperationJournalContract,
    private val operationLock: ControlOperationLock
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend operator fun invoke(groupId: String, removePubkey: String) {
        withContext(NonCancellable) {
            operationLock.withLock {
                check(
                    identity.stagedIdentitySwitch() == null && journal.get(IdentitySwitchCoordinator.SWITCH_ID) == null
                ) {
                    "Finish the pending identity switch first"
                }
                reconcileIdentityOperations(identity, journal)
                check(journal.get(REVOCATION_ID) == null && !identity.hasPendingKeyPair()) {
                    "Finish the pending identity revocation before removing members"
                }
                val pending = journal.get(operationId(groupId)) ?: importLegacyRotation(groupId)
                if (pending != null) {
                    val previous = json.decodeFromString<RotationIntent>(pending.intentJson)
                    finish(pending)
                    if (previous.removedMember == removePubkey) return@withLock
                }
                val group = groupRepo.getById(groupId) ?: error("Group $groupId not found")
                val me = identity.getPublicKeyHex()
                check(group.createdBy == me) { "Only the group creator can remove members" }
                check(removePubkey in group.members) { "Member not in group" }
                check(removePubkey != me) { "Cannot remove yourself" }
                check(group.keyEpoch < Int.MAX_VALUE) { "Group epoch exhausted" }
                val intent = RotationIntent(group, removePubkey, group.keyEpoch + 1)
                val operation = ControlOperation(operationId(groupId), ROTATION_KIND, json.encodeToString(intent))
                journal.insert(operation)
                finish(operation)
            }
        }
    }

    suspend fun resumeIfNeeded() {
        withContext(NonCancellable) {
            operationLock.withLock {
                check(
                    identity.stagedIdentitySwitch() == null && journal.get(IdentitySwitchCoordinator.SWITCH_ID) == null
                ) {
                    "Finish the pending identity switch first"
                }
                reconcileIdentityOperations(identity, journal)
                val me = identity.getPublicKeyHex()
                val pending = journal.getAll(ROTATION_KIND).associateBy { it.id }.toMutableMap()
                for (group in groupRepo.getAll()) {
                    if (group.createdBy != me || operationId(group.id) in pending) continue
                    try {
                        importLegacyRotation(group.id)?.let { pending[it.id] = it }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not recover legacy rotation for ${group.id.take(8)}: ${e.message}")
                    }
                }
                for (operation in pending.values) {
                    try {
                        finish(operation)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not resume ${operation.id}: ${e.message}")
                    }
                }
            }
        }
    }

    private suspend fun finish(operation: ControlOperation) {
        var op = operation
        var intent = json.decodeFromString<RotationIntent>(op.intentJson)
        val groupId = intent.group.id
        check(identity.getPublicKeyHex() == intent.group.createdBy) { "Rotation belongs to a different identity" }
        val live = groupRepo.getById(groupId) ?: error("Group $groupId not found")
        check(live.createdBy == intent.group.createdBy) { "Creator authority changed during rotation" }
        val landed = live.keyEpoch == intent.epoch
        check(landed || live.keyEpoch == intent.group.keyEpoch) {
            "Group ${groupId.take(8)} is at epoch ${live.keyEpoch}, rotation targets ${intent.epoch}"
        }
        // Resolved against the snapshot before any rebase: once the intent follows the live roster the
        // successor is already gone from it, so a later resume keeps recognising it as departing.
        val departing = departingSuccessors(intent)
        if (op.preparedJson == null && live != intent.group) {
            // No durable prepared batch was published: rebase instead of retaining a stale roster.
            check(!landed) { "Epoch ${intent.epoch} advanced without this device publishing it" }
            val rebased = RotationIntent(
                live.copy(members = live.members - departing, memberNames = live.memberNames - departing),
                intent.removedMember,
                intent.epoch
            )
            val rebasedJson = json.encodeToString(rebased)
            journal.rebase(op.id, op.intentJson, rebasedJson)
            op = op.copy(intentJson = rebasedJson)
            intent = rebased
        }
        val target = if (landed) live.members else live.members - intent.removedMember - departing

        val events: List<PreparedControlEvent>
        if (op.preparedJson == null) {
            // Intent lands first. A crash during the secure write can only resume this same removal.
            val key = groupRepo.getGroupKeyForEpoch(groupId, intent.epoch)
                ?: encryption.generateGroupKey().also { groupRepo.saveGroupKeyForEpoch(groupId, intent.epoch, it) }
            val rotation = KeyRotation(intent.epoch, encryptKeyForMembers(key, target), target, intent.removedMember)
            events = prepareRotation(groupId, rotation, target) + prepareMeta(live, target, intent.epoch, key)
            journal.prepare(op.id, json.encodeToString(events))
        } else {
            val key = checkNotNull(groupRepo.getGroupKeyForEpoch(groupId, intent.epoch)) {
                "Prepared rotation key unavailable"
            }
            events = extendPlan(op, intent, live, target, key)
        }
        val rotations = events.filter { it.eventType == "key_rotation" }.map { envelope ->
            envelope.event() to decryptPreparedRotation(envelope)
        }
        val epochKey =
            checkNotNull(groupRepo.getGroupKeyForEpoch(groupId, intent.epoch)) { "Prepared rotation key unavailable" }
        val metas = events.filter { it.eventType == "group_meta" }.map { envelope ->
            val event = envelope.event()
            event to json.decodeFromString<GroupMeta>(encryption.decrypt(event.content, epochKey))
        }
        check(
            rotations.isNotEmpty() &&
                metas.isNotEmpty() &&
                rotations.all { (event, payload) ->
                    event.pubkey == intent.group.createdBy &&
                        payload.epoch == intent.epoch &&
                        payload.removedMember == intent.removedMember
                } &&
                metas.all { (event, payload) ->
                    event.pubkey == intent.group.createdBy && payload.keyEpoch == intent.epoch
                }
        ) {
            "Prepared rotation does not match its authenticated intent"
        }
        events.filter { it.eventType == "key_rotation" }.forEach { it.publish(eventPublisher) }
        for ((index, pair) in rotations.withIndex()) {
            val (event, payload) = pair
            check(
                groupRepo.applyAuthenticatedRotation(
                    groupId,
                    payload,
                    event.pubkey,
                    event.createdAt,
                    event.id,
                    expectedMembers = if (!landed && index == 0) live.members else null
                )
            ) {
                "Group changed during rotation"
            }
        }
        events.filter { it.eventType == "group_meta" }.forEach { it.publish(eventPublisher) }
        for ((event, payload) in metas) {
            check(
                groupRepo.applyAuthenticatedMeta(
                    groupId,
                    payload,
                    event.pubkey,
                    event.createdAt,
                    event.id,
                    intent.epoch
                )
            ) {
                "Could not project prepared rotation metadata"
            }
        }
        journal.complete(op.id)
    }

    private fun decryptPreparedRotation(envelope: PreparedControlEvent): KeyRotation {
        val event = envelope.event()
        val recipient = checkNotNull(envelope.recipient()) { "Prepared rotation recipient missing" }
        val privateKey = identity.getPrivateKeyBytes()
        try {
            val conversationKey = Nip44.getConversationKey(privateKey, recipient.hexToBytes())
            try {
                return json.decodeFromString<KeyRotation>(Nip44.decrypt(event.content, conversationKey))
            } finally {
                conversationKey.fill(0)
            }
        } finally {
            privateKey.fill(0)
        }
    }

    /**
     * The identities the removed key moved to through revocations recorded since the intent's snapshot,
     * which leave the roster with it. A successor who already sat in the snapshot is an independent
     * member: the repository records the `replaced` link in that case too (the revoked key is merely
     * dropped), and a key being removed must not be able to take such a member out by revoking to it.
     */
    private suspend fun departingSuccessors(intent: RotationIntent): List<String> =
        groupRepo.resolveRoster(intent.group.id, listOf(intent.removedMember))
            .filter { it != intent.removedMember && it !in intent.group.members }

    /**
     * Keeps every event of a prepared plan and appends what the live roster needs beyond it: an envelope
     * for each member without one, and a corrective metadata event whenever the newest prepared metadata
     * does not carry [target]. Only the newest counts, because that is the one receivers keep: after a
     * roster that moved away and back, an older matching metadata is already superseded by the plan's own
     * correction. Amended atomically before anything new is published; the plan is returned as it is when
     * nothing is missing.
     */
    private suspend fun extendPlan(
        op: ControlOperation,
        intent: RotationIntent,
        live: Group,
        target: List<String>,
        key: String
    ): List<PreparedControlEvent> {
        val original = json.decodeFromString<List<PreparedControlEvent>>(checkNotNull(op.preparedJson))
        val covered = original.filter { it.eventType == "key_rotation" }.mapNotNullTo(HashSet()) { it.recipient() }
        val missing = target.filter { it !in covered }
        val extended = original.toMutableList()
        if (missing.isNotEmpty()) {
            val rotation = KeyRotation(intent.epoch, encryptKeyForMembers(key, target), target, intent.removedMember)
            extended += prepareRotation(intent.group.id, rotation, missing)
        }
        val metas = original.filter { it.eventType == "group_meta" }.map { it.event() }
        val newest = metas.maxWithOrNull(compareBy<NostrEvent>({ it.createdAt }, { it.id }))
        val newestRoster = newest?.let { meta ->
            runCatching { json.decodeFromString<GroupMeta>(encryption.decrypt(meta.content, key)) }
                .getOrNull()?.members?.toSet()
        }
        val corrected = newestRoster == target.toSet()
        if (!corrected) {
            val notBefore = metas.maxOfOrNull { it.createdAt + 1 } ?: 0L
            extended += prepareMeta(live, target, intent.epoch, key, notBefore)
        }
        if (extended.size == original.size) return original
        Log.i(
            TAG,
            "Rotation to epoch ${intent.epoch} of ${intent.group.id.take(8)}: plan extended for the live roster, " +
                "${missing.size} envelope(s) and ${if (corrected) 0 else 1} corrective meta appended"
        )
        val amended = json.encodeToString(extended)
        journal.amend(op.id, checkNotNull(op.preparedJson), amended)
        return extended
    }

    private fun PreparedControlEvent.recipient(): String? =
        event().tags.firstOrNull { it.size >= 2 && it[0] == "p" }?.get(1)

    private fun encryptKeyForMembers(groupKey: String, members: List<String>): Map<String, String> {
        val privKey = identity.getPrivateKeyBytes()
        try {
            return members.associateWith { Nip44.encrypt(groupKey, Nip44.getConversationKey(privKey, it.hexToBytes())) }
        } finally {
            privKey.fill(0)
        }
    }

    private fun prepareRotation(
        groupId: String,
        rotation: KeyRotation,
        recipients: List<String>
    ): List<PreparedControlEvent> {
        val payload = json.encodeToString(rotation)
        val privKey = identity.getPrivateKeyBytes()
        try {
            return recipients.map { recipient ->
                val encrypted = Nip44.encrypt(payload, Nip44.getConversationKey(privKey, recipient.hexToBytes()))
                val event = signer.createSignedEvent(
                    groupId,
                    "key_rotation",
                    encrypted,
                    recipientPubkey = recipient
                )
                PreparedControlEvent(groupId, "key_rotation", event.toJson())
            }
        } finally {
            privKey.fill(0)
        }
    }

    /**
     * The post-rotation metadata for [members] under [epoch]. [notBefore] dates a corrective event
     * strictly after the plan event it supersedes so the creator watermark orders them correctly.
     */
    private fun prepareMeta(
        group: Group,
        members: List<String>,
        epoch: Int,
        key: String,
        notBefore: Long = 0L
    ): PreparedControlEvent {
        val meta = GroupMeta(
            group.name,
            group.description,
            group.createdBy,
            group.createdAt,
            members,
            group.relays,
            group.memberNames.filterKeys { it in members },
            keyEpoch = epoch,
            originalCreator = group.originalCreator.takeIf {
                com.splitfree.domain.model.group.GroupIdentity.matches(group.id, it, group.createdAt)
            }.orEmpty(),
            creatorTransitions = group.creatorTransitions
        )
        val encrypted = encryption.encrypt(json.encodeToString(meta), key)
        val event = if (notBefore > 0L) {
            val now = System.currentTimeMillis() / 1000
            signer.createSignedEvent(group.id, "group_meta", encrypted, createdAt = maxOf(now, notBefore))
        } else {
            signer.createSignedEvent(group.id, "group_meta", encrypted)
        }
        return PreparedControlEvent(group.id, "group_meta", event.toJson())
    }

    private suspend fun importLegacyRotation(groupId: String): ControlOperation? {
        val group = groupRepo.getById(groupId) ?: return null
        val me = identity.getPublicKeyHex()
        if (group.createdBy != me) return null
        val epoch = group.keyEpoch + 1
        val key = groupRepo.getGroupKeyForEpoch(groupId, epoch) ?: return null
        val privKey = identity.getPrivateKeyBytes()
        val found = mutableListOf<Pair<KeyRotation, PreparedControlEvent>>()
        try {
            for (row in eventRepo.getEventsByType(groupId, "key_rotation")) {
                if (row.pubkey != me) continue
                val event = row.originalEventJson?.let { NostrEvent.fromJson(it) } ?: continue
                val recipient = event.tags.firstOrNull { it.size >= 2 && it[0] == "p" }?.get(1) ?: continue
                val rotation = try {
                    json.decodeFromString<KeyRotation>(
                        Nip44.decrypt(event.content, Nip44.getConversationKey(privKey, recipient.hexToBytes()))
                    )
                } catch (_: Exception) {
                    continue
                }
                if (rotation.epoch ==
                    epoch
                ) {
                    found += rotation to PreparedControlEvent(groupId, "key_rotation", event.toJson())
                }
            }
        } finally {
            privKey.fill(0)
        }
        // Without durable intent or an envelope there is no safe way to infer the old target.
        check(found.isNotEmpty()) { "Legacy epoch key has ambiguous removal intent; refusing to retarget it" }
        val rotation = found.first().first
        check(found.all { it.first == rotation }) { "Conflicting legacy rotation intents" }
        check(rotation.removedMember in group.members && rotation.members == group.members - rotation.removedMember) {
            "Legacy rotation roster does not match the group"
        }
        val intent = RotationIntent(group, rotation.removedMember, epoch)
        val operation = ControlOperation(operationId(groupId), ROTATION_KIND, json.encodeToString(intent))
        val existing = found.map { it.second }
        val recipients = existing.mapNotNull {
            it.event().tags.firstOrNull { tag -> tag.size >= 2 && tag[0] == "p" }?.get(1)
        }
        val events =
            existing + prepareRotation(groupId, rotation, rotation.members - recipients.toSet()) +
                prepareMeta(group, rotation.members, epoch, key)
        val prepared = json.encodeToString(events)
        return operation.copy(preparedJson = prepared).also { journal.insert(it) }
    }

    /**
     * Handle an incoming key_rotation event: decrypt new key and update local state.
     *
     * @param createdAt signed event time retained with [eventId] for canonical projection ordering
     * @return [RotationOutcome.APPLIED] once the epoch and roster are installed (or my own removal
     *   recorded), [RotationOutcome.DEFERRED_EPOCH_GAP] when an earlier epoch has not landed yet,
     *   [RotationOutcome.DEFERRED_MEMBERSHIP] when it names a member whose join has not landed yet
     *   (caller keeps the row pending and retries both), [RotationOutcome.IGNORED] for a replay of an
     *   older epoch (or the current epoch without an event id). Same-epoch events with ids still reach
     *   projection. [RotationOutcome.REJECTED] covers invalid input, authority, key or projection conflicts
     */
    suspend fun handleKeyRotation(
        decryptedContent: String,
        authorPubkey: String,
        groupId: String,
        createdAt: Long,
        eventId: String = ""
    ): RotationOutcome {
        val rotation = try {
            json.decodeFromString<KeyRotation>(decryptedContent)
        } catch (e: Exception) {
            Log.w(TAG, "Invalid key_rotation payload: ${e.message}")
            return RotationOutcome.REJECTED
        }

        return operationLock.withLock {
            applyKeyRotation(rotation, authorPubkey, groupId, createdAt, eventId)
        }
    }

    private suspend fun applyKeyRotation(
        rotation: KeyRotation,
        authorPubkey: String,
        groupId: String,
        createdAt: Long,
        eventId: String
    ): RotationOutcome {
        val group = groupRepo.getById(groupId) ?: return RotationOutcome.REJECTED
        if (authorPubkey != group.createdBy) {
            Log.w(TAG, "Ignoring key_rotation from non-creator $authorPubkey")
            return RotationOutcome.REJECTED
        }

        // Ignore stale epochs before evaluating removal, so an old rotation cannot undo a re-invite.
        // Same-epoch envelopes with ids still carry facts needed by the canonical projection.
        if (group.keyEpoch > rotation.epoch || (group.keyEpoch == rotation.epoch && eventId.isEmpty())) {
            return RotationOutcome.IGNORED
        }

        // Rotations must be applied strictly in sequence. Skipping an epoch would leave the
        // intermediate history undecryptable (we would never receive that epoch's key), so the
        // row stays pending until the missing epoch arrives (relay catch-up or a nearby peer).
        if (rotation.epoch > group.keyEpoch + 1) {
            Log.w(
                TAG,
                "Deferring key_rotation for epoch ${rotation.epoch} in $groupId, local epoch is ${group.keyEpoch}"
            )
            return RotationOutcome.DEFERRED_EPOCH_GAP
        }

        // The creator may not have seen a revocation this device applied; its roster then names the
        // revoked key, which resolves to the recorded successor here (or drops out).
        val members = groupRepo.resolveRoster(groupId, rotation.members)
        // The roster after rotation may not introduce anyone this device has not seen join: that is
        // a missing earlier update (their group_meta), not an invalid rotation.
        val known = group.members.toSet()
        val unseen = members.filter { it !in known }
        if (unseen.isNotEmpty()) {
            Log.w(TAG, "Deferring key_rotation for epoch ${rotation.epoch}: ${unseen.size} member(s) not yet joined")
            return RotationOutcome.DEFERRED_MEMBERSHIP
        }
        if (rotation.removedMember.isNotEmpty() && rotation.removedMember !in known) {
            Log.i(TAG, "key_rotation removes ${rotation.removedMember.take(8)} whom this device never saw")
        }

        val myPubkey = identity.getPublicKeyHex()
        val myEncryptedKey = rotation.encryptedKeys[myPubkey]
        if (myPubkey !in members || (myEncryptedKey == null && myPubkey !in rotation.members)) {
            // A successor cannot decrypt a key addressed only to its retired identity. Record the
            // rotation without installing a key here; possession must not follow roster succession.
            Log.i(TAG, "No key for me in group $groupId at epoch ${rotation.epoch} (created_at $createdAt)")
            return if (groupRepo.applyAuthenticatedRotation(groupId, rotation, authorPubkey, createdAt, eventId)) {
                RotationOutcome.APPLIED
            } else {
                RotationOutcome.REJECTED
            }
        }
        if (myEncryptedKey == null) {
            Log.w(TAG, "No encrypted key for me in key_rotation")
            return RotationOutcome.REJECTED
        }

        val privKey = identity.getPrivateKeyBytes()
        val newGroupKey: String
        try {
            val creatorPubBytes = authorPubkey.hexToBytes()
            val convKey = Nip44.getConversationKey(privKey, creatorPubBytes)
            newGroupKey = Nip44.decrypt(myEncryptedKey, convKey)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decrypt new group key: ${e.message}")
            return RotationOutcome.REJECTED
        } finally {
            privKey.fill(0)
        }

        // Key material for an epoch is immutable once written. If this device already holds a
        // key for `rotation.epoch` (crash between saveGroupKeyForEpoch and applyKeyRotation, or a
        // second per-member envelope of the same rotation), it must be byte-identical: a different
        // key would mean two rotations claim the same epoch and we must not silently overwrite.
        val existing = groupRepo.getGroupKeyForEpoch(groupId, rotation.epoch)
        if (existing != null && existing != newGroupKey) {
            Log.w(TAG, "key_rotation for epoch ${rotation.epoch} in $groupId carries conflicting key material")
            return RotationOutcome.REJECTED
        }
        if (existing == null) {
            groupRepo.saveGroupKeyForEpoch(groupId, rotation.epoch, newGroupKey)
        } else {
            Log.i(TAG, "Epoch ${rotation.epoch} key already stored for $groupId, resuming interrupted rotation")
        }
        // Epoch and roster land together: a crash can never leave the new epoch with the old roster.
        if (!groupRepo.applyAuthenticatedRotation(groupId, rotation, authorPubkey, createdAt, eventId)) {
            return RotationOutcome.REJECTED
        }

        Log.i(TAG, "Applied key rotation for group $groupId: epoch ${rotation.epoch}")
        return RotationOutcome.APPLIED
    }

    companion object {
        private const val TAG = "RotateGroupKeyUseCase"
        internal const val ROTATION_KIND = "rotation"
        internal const val REVOCATION_ID = "identity-revocation"
        private fun operationId(groupId: String) = "rotation:$groupId"
    }
}
