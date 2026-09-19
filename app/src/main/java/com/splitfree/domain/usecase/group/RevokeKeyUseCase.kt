package com.splitfree.domain.usecase.group

import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.group.CreatorTransition
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupIdentity
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.model.group.IdentityHistory
import com.splitfree.domain.model.group.IdentityHistoryPage
import com.splitfree.domain.model.group.KeyRevocation
import com.splitfree.domain.repository.ControlOperation
import com.splitfree.domain.repository.ControlOperationJournalContract
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.MembershipHistoryContract
import com.splitfree.domain.repository.SettingsContract
import com.splitfree.util.DebugLog as Log
import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RevokeKeyUseCase
@Inject
constructor(
    private val identity: IdentityContract,
    private val groupRepo: GroupRepositoryContract,
    private val encryption: GroupEncryption,
    private val signer: EventSigner,
    private val eventPublisher: EventPublisherContract,
    private val journal: ControlOperationJournalContract,
    private val operationLock: ControlOperationLock,
    private val settings: SettingsContract,
    private val membershipHistory: MembershipHistoryContract
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend operator fun invoke(): String = withContext(NonCancellable) {
        operationLock.withLock {
            check(identity.stagedIdentitySwitch() == null && journal.get(IdentitySwitchCoordinator.SWITCH_ID) == null) {
                "Finish the pending identity switch first"
            }
            reconcileIdentityOperations(identity, journal)
            val existing = journal.get(RotateGroupKeyUseCase.REVOCATION_ID)
            if (existing != null) return@withLock finish(existing)
            check(!identity.hasPendingKeyPair()) {
                "Legacy pending identity has ambiguous publication state; preserve it for recovery"
            }
            check(journal.getAll(RotateGroupKeyUseCase.ROTATION_KIND).isEmpty()) {
                "Finish pending group rotations before revoking the identity"
            }
            val me = identity.getPublicKeyHex()
            val intent = RevocationIntent(me, groupRepo.getAll().filter { me in it.members })
            // Fail closed BEFORE the intent lands: a group whose current key is missing could not be
            // told about the new identity, and promoting anyway would lock this user out of it. A
            // journaled intent that can never prepare would also block every rotation.
            for (group in intent.groups) {
                check(group.createdBy != me || group.creatorTransitions.size < CreatorTransition.MAX_TRANSITIONS) {
                    "Creator authority history limit reached; revocation not started"
                }
                checkNotNull(groupRepo.getGroupKeyForEpoch(group.id, group.keyEpoch)) {
                    "Current-epoch key unavailable for group ${group.id}; revocation not started"
                }
            }
            var captured: ControlOperation? = null
            eventPublisher.captureRevocationHistory(me, intent.groups) { history ->
                val validator = IdentityHistory(groupRepo, encryption)
                val ids = history.mapValues { (groupId, rows) ->
                    val members = intent.groups.single { it.id == groupId }.members.toSet() +
                        membershipHistory.formerMembers(groupId)
                    check(
                        rows.all { row ->
                            row.applyState == 0 && validator.validMoney(row, members, rows, requireSignature = true)
                        }
                    ) {
                        "Retained money history is incomplete or unverifiable. Restore original signed events before replacing identity"
                    }
                    rows.map { it.eventId }.distinct().sorted()
                }
                val operation = ControlOperation(
                    RotateGroupKeyUseCase.REVOCATION_ID,
                    "revocation",
                    json.encodeToString(intent.copy(moneyHistory = ids))
                )
                journal.insert(operation)
                captured = operation
            }
            finish(checkNotNull(captured) { "History capture did not persist a revocation intent" })
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
                val operation = journal.get(RotateGroupKeyUseCase.REVOCATION_ID)
                if (operation != null) {
                    finish(operation)
                } else if (identity.hasPendingKeyPair()) {
                    // A pending key with no journaled intent has unknown publication state: empty tracking
                    // IDs, its age or an empty outbox prove nothing about whether its revocation went out.
                    Log.w(TAG, "Preserving legacy pending identity with ambiguous publication state")
                }
            }
        }
    }

    private suspend fun finish(operation: ControlOperation): String {
        val intent = json.decodeFromString<RevocationIntent>(operation.intentJson)
        val preparedJson = operation.preparedJson ?: run {
            check(identity.getPublicKeyHex() == intent.oldPubkey) { "Revocation belongs to a different identity" }
            val newPubkey = identity.getPendingPublicKeyHex() ?: identity.generatePendingKeyPair()
            identity.markRevocationStarted()
            val events = withReplacementKey { newPrivateKey ->
                intent.groups.flatMap { group ->
                    val key = checkNotNull(groupRepo.getGroupKeyForEpoch(group.id, group.keyEpoch)) {
                        "Current-epoch key unavailable for group ${group.id}; revocation not published"
                    }
                    prepareRevocation(group, key, intent.oldPubkey, newPubkey, newPrivateKey)
                }
            }
            val historyEvents = withReplacementKey { privateKey ->
                intent.moneyHistory?.let { history ->
                    intent.groups.flatMap { group ->
                        val revocation = events.single {
                            it.groupId == group.id && it.eventType == "key_revocation"
                        }.event()
                        val key = checkNotNull(groupRepo.getGroupKeyForEpoch(group.id, group.keyEpoch))
                        IdentityHistoryPage.create(
                            group.id,
                            intent.oldPubkey,
                            newPubkey,
                            revocation,
                            history.getValue(group.id)
                        )
                            .map { page ->
                                val payload = json.encodeToString(page)
                                val event = NostrEvent(
                                    pubkey = newPubkey,
                                    createdAt = revocation.createdAt,
                                    kind = 30078,
                                    tags = listOf(
                                        listOf("g", group.id),
                                        listOf("t", IdentityHistoryPage.TYPE),
                                        listOf(
                                            "d",
                                            "${group.id}:history:${revocation.id}:${page.root}:${page.pageIndex}"
                                        )
                                    ),
                                    content = encryption.encrypt(payload, key)
                                ).sign(privateKey)
                                PreparedControlEvent(
                                    group.id,
                                    IdentityHistoryPage.TYPE,
                                    event.toJson(),
                                    PreparedRevocationProjection.authenticate(
                                        event.id,
                                        payload,
                                        group.keyEpoch,
                                        privateKey
                                    )
                                )
                            }
                    }
                }.orEmpty()
            }
            json.encodeToString(PreparedRevocation(newPubkey, events, historyEvents)).also {
                journal.prepare(operation.id, it)
            }
        }
        var prepared = json.decodeFromString<PreparedRevocation>(preparedJson)
        check(identity.getPublicKeyHex() == intent.oldPubkey || identity.getPublicKeyHex() == prepared.newPubkey) {
            "Revocation belongs to a different identity"
        }
        check(
            identity.getPublicKeyHex() == prepared.newPubkey || identity.getPendingPublicKeyHex() == prepared.newPubkey
        ) {
            "Journaled replacement identity unavailable"
        }
        prepared = validateAndUpgrade(operation.id, intent, prepared, preparedJson)
        if (identity.getPublicKeyHex() == intent.oldPubkey) {
            intent.groups.forEach { membershipHistory.retainRotationHistory(it.id) }
        }
        // Tracking IDs are informational only: neither proof of publication nor authorization to discard a key.
        identity.setRevocationEventIds((prepared.events + prepared.historyEvents).map { it.event().id })
        prepared.events.filter { it.eventType == "key_revocation" }.forEach { it.publish(eventPublisher) }
        prepared.historyEvents.forEach { envelope ->
            eventPublisher.publishIdentityHistory(
                envelope.event(),
                envelope.groupId,
                checkNotNull(envelope.projection).epoch
            )
        }
        prepared.events.filter { it.eventType == "group_meta" }.forEach { it.publish(eventPublisher) }
        for (revocation in prepared.events.filter { it.eventType == "key_revocation" }) {
            val groupId = revocation.groupId
            if (groupRepo.getById(groupId) == null) {
                Log.w(TAG, "Group ${groupId.take(8)} deleted locally; revocation published but not projected")
                continue
            }
            val event = revocation.event()
            val projected =
                applyRevocation(
                    checkNotNull(revocation.projection).payload,
                    event.pubkey,
                    groupId,
                    event.createdAt,
                    event.id,
                    own = true,
                    epoch = checkNotNull(revocation.projection).epoch
                )
            check(projected || groupRepo.getById(groupId) == null) {
                "Could not project revocation for group $groupId"
            }
        }
        for (envelope in prepared.events.filter { it.eventType == "group_meta" }) {
            val meta = json.decodeFromString<GroupMeta>(checkNotNull(envelope.projection).payload)
            if (meta.creatorTransitions.isNotEmpty() && groupRepo.getById(envelope.groupId) != null) {
                check(
                    groupRepo.mergeCreatorBootstrap(
                        envelope.groupId,
                        meta.originalCreator,
                        meta.createdAt,
                        meta.creatorTransitions
                    )
                ) { "Creator authority proof could not be persisted" }
            }
        }
        synchronized(settings) {
            if (identity.getPublicKeyHex() == intent.oldPubkey ||
                settings.getDisplayNameIntent(prepared.newPubkey) == null
            ) {
                settings.transferDisplayNameIntent(intent.oldPubkey, prepared.newPubkey)
            }
            identity.finishPendingKeyPair(prepared.newPubkey)
        }
        journal.complete(operation.id)
        return prepared.newPubkey
    }

    private suspend fun validateAndUpgrade(
        operationId: String,
        intent: RevocationIntent,
        prepared: PreparedRevocation,
        preparedJson: String
    ): PreparedRevocation {
        val expected = intent.groups.flatMap { group ->
            listOf(group.id to "key_revocation", group.id to "group_meta")
        }
        check(
            expected.distinct().size == expected.size &&
                prepared.events.map { it.groupId to it.eventType }.sortedBy { it.toString() } ==
                expected.sortedBy { it.toString() }
        ) { "Incomplete or duplicate revocation batch" }
        val events = prepared.events.map { envelope ->
            val event = envelope.event()
            check(
                event.verify() &&
                    event.createdAt > 0 &&
                    event.pubkey == intent.oldPubkey &&
                    event.kind == 30078 &&
                    event.tags.filter { it.firstOrNull() == "g" } == listOf(listOf("g", envelope.groupId)) &&
                    event.tags.filter { it.firstOrNull() == "t" } == listOf(listOf("t", envelope.eventType))
            ) {
                "Invalid authenticated revocation envelope"
            }
            val projection = envelope.projection ?: run {
                val group = intent.groups.single { it.id == envelope.groupId }
                val key = checkNotNull(groupRepo.getGroupKeyForEpoch(group.id, group.keyEpoch)) {
                    "Epoch ${group.keyEpoch} key unavailable for group ${group.id}; legacy revocation not published"
                }
                val payload = encryption.decrypt(event.content, key)
                withSuccessorKey(prepared.newPubkey) { privateKey ->
                    PreparedRevocationProjection.authenticate(event.id, payload, group.keyEpoch, privateKey)
                }
            }
            check(
                projection.epoch == intent.groups.single { it.id == envelope.groupId }.keyEpoch &&
                    projection.verify(event.id, prepared.newPubkey)
            ) { "Invalid journal projection authentication" }
            if (envelope.eventType == "key_revocation") {
                val payload = json.decodeFromString<KeyRevocation>(projection.payload)
                check(payload.isAuthorizedBy(event.pubkey) && payload.newPubkey == prepared.newPubkey) {
                    "Journal projection does not match the revoked identity and successor"
                }
            } else {
                val group = intent.groups.single { it.id == envelope.groupId }
                val meta = json.decodeFromString<GroupMeta>(projection.payload)
                if (meta.creatorTransitions.isNotEmpty()) {
                    check(
                        CreatorTransition.validate(
                            group.id,
                            meta.originalCreator,
                            meta.createdAt,
                            meta.creatorTransitions
                        )
                    ) {
                        "Invalid journal creator proof"
                    }
                    if (group.createdBy == intent.oldPubkey) {
                        val original = prepared.events.single {
                            it.groupId == group.id &&
                                it.eventType == "key_revocation"
                        }.event()
                        val proof = meta.creatorTransitions.single { it.eventId == original.id }
                        check(
                            proof.oldPubkey == original.pubkey &&
                                proof.newPubkey == prepared.newPubkey &&
                                proof.timestamp == original.createdAt &&
                                proof.epoch == group.keyEpoch
                        ) {
                            "Creator certificate disagrees with prepared revocation"
                        }
                    }
                }
                check(
                    meta.keyEpoch == group.keyEpoch &&
                        meta.createdAt == group.createdAt &&
                        meta.createdBy == (
                            if (group.createdBy ==
                                intent.oldPubkey
                            ) {
                                prepared.newPubkey
                            } else {
                                group.createdBy
                            }
                            ) &&
                        meta.members ==
                        group.members.map { if (it == intent.oldPubkey) prepared.newPubkey else it }.distinct()
                ) {
                    "Journal metadata does not match the intended successor and group epoch"
                }
            }
            envelope.copy(projection = projection)
        }
        val expectedHistory = intent.moneyHistory?.let { history ->
            intent.groups.flatMap { group ->
                val revocation = events.single { it.groupId == group.id && it.eventType == "key_revocation" }.event()
                IdentityHistoryPage.create(
                    group.id,
                    intent.oldPubkey,
                    prepared.newPubkey,
                    revocation,
                    history.getValue(group.id)
                )
            }
        }.orEmpty()
        val actualHistory = prepared.historyEvents.map { envelope ->
            val event = envelope.event()
            val projection = checkNotNull(envelope.projection)
            val group = intent.groups.single { it.id == envelope.groupId }
            check(
                event.verify() &&
                    event.pubkey == prepared.newPubkey &&
                    event.kind == 30078 &&
                    envelope.eventType == IdentityHistoryPage.TYPE &&
                    projection.epoch == group.keyEpoch &&
                    projection.verify(event.id, prepared.newPubkey) &&
                    event.tags.filter { it.firstOrNull() == "g" } == listOf(listOf("g", group.id)) &&
                    event.tags.filter { it.firstOrNull() == "t" } == listOf(listOf("t", IdentityHistoryPage.TYPE))
            ) {
                "Invalid authenticated identity history envelope"
            }
            json.decodeFromString<IdentityHistoryPage>(projection.payload)
        }
        check(actualHistory == expectedHistory) { "Incomplete or altered identity history batch" }
        val upgraded = prepared.copy(events = events)
        if (upgraded != prepared) journal.amend(operationId, preparedJson, json.encodeToString(upgraded))
        return upgraded
    }

    private inline fun <T> withSuccessorKey(successor: String, block: (ByteArray) -> T): T {
        val privateKey = if (identity.getPublicKeyHex() == successor) {
            identity.getPrivateKeyBytes()
        } else {
            check(identity.getPendingPublicKeyHex() == successor) { "Journal successor unavailable" }
            checkNotNull(identity.getPendingPrivateKeyBytes()) { "Journal successor unavailable" }
        }
        try {
            return block(privateKey)
        } finally {
            privateKey.fill(0)
        }
    }

    /**
     * Borrow the pending key for successor proofs and authenticated journal payloads; zero the copy
     * afterward. Applying a prepared projection needs no private key.
     */
    private inline fun <T> withReplacementKey(block: (ByteArray) -> T): T {
        val newPrivateKey =
            checkNotNull(identity.getPendingPrivateKeyBytes()) { "Journaled replacement identity unavailable" }
        try {
            return block(newPrivateKey)
        } finally {
            newPrivateKey.fill(0)
        }
    }

    /** The `key_revocation` content: signed for by the old key as the event, proven for the new key inside. */
    private fun revocationPayload(
        groupId: String,
        oldPubkey: String,
        newPubkey: String,
        newPrivateKey: ByteArray
    ): String = json.encodeToString(
        KeyRevocation(
            oldPubkey,
            newPubkey,
            "Key compromised",
            successorProof = KeyRevocation.proveSuccessor(groupId, oldPubkey, newPubkey, newPrivateKey)
        )
    )

    private fun prepareRevocation(
        group: Group,
        groupKey: String,
        oldPubkey: String,
        newPubkey: String,
        newPrivateKey: ByteArray
    ): List<PreparedControlEvent> {
        val names = group.memberNames.toMutableMap().apply {
            val oldName = remove(oldPubkey)
            if (oldName != null && newPubkey !in this) put(newPubkey, oldName)
        }
        val revocationJson = revocationPayload(group.id, oldPubkey, newPubkey, newPrivateKey)
        val revocationEvent = signer.createSignedEvent(
            group.id,
            "key_revocation",
            encryption.encrypt(revocationJson, groupKey)
        )
        val transitions = if (group.createdBy == oldPubkey &&
            GroupIdentity.matches(group.id, group.originalCreator, group.createdAt)
        ) {
            val oldPrivateKey = identity.getPrivateKeyBytes()
            try {
                group.creatorTransitions + CreatorTransition.sign(
                    group.id,
                    revocationEvent,
                    group.keyEpoch,
                    json.decodeFromString<KeyRevocation>(revocationJson),
                    oldPrivateKey
                )
            } finally {
                oldPrivateKey.fill(0)
            }
        } else {
            group.creatorTransitions
        }
        val meta = GroupMeta(
            group.name,
            group.description,
            if (group.createdBy == oldPubkey) newPubkey else group.createdBy,
            group.createdAt,
            group.members.map { if (it == oldPubkey) newPubkey else it }.distinct(),
            group.relays,
            names,
            keyEpoch = group.keyEpoch,
            originalCreator = group.originalCreator.takeIf {
                GroupIdentity.matches(group.id, it, group.createdAt)
            }.orEmpty(),
            creatorTransitions = transitions
        )
        val payloads = listOf(
            "key_revocation" to revocationJson,
            "group_meta" to json.encodeToString(meta)
        )
        return payloads.map { (type, payload) ->
            val event = if (type == "key_revocation") {
                revocationEvent
            } else {
                signer.createSignedEvent(group.id, type, encryption.encrypt(payload, groupKey))
            }
            PreparedControlEvent(
                group.id,
                type,
                event.toJson(),
                PreparedRevocationProjection.authenticate(event.id, payload, group.keyEpoch, newPrivateKey)
            )
        }
    }

    /**
     * Apply an already-authenticated retirement on the compatibility ingestion path. Tombstones
     * prevent later metadata from reintroducing the old key; a successor is optional.
     *
     * @param createdAt signed event time used to order the retirement fact
     * @param eventId signed event id used as the clock tiebreak
     */
    suspend fun handleRevocation(
        decryptedContent: String,
        authorPubkey: String,
        groupId: String,
        createdAt: Long,
        eventId: String
    ): Boolean = operationLock.withLock {
        applyRevocation(decryptedContent, authorPubkey, groupId, createdAt, eventId, own = false)
    }

    /**
     * @param own true only for this device's journaled revocation: its tombstone is recorded even when
     *   the roster no longer holds either identity. A remote author must still be (or have been) a
     *   member, which ingestion already checks before the effect runs.
     */
    private suspend fun applyRevocation(
        decryptedContent: String,
        authorPubkey: String,
        groupId: String,
        createdAt: Long,
        eventId: String,
        own: Boolean,
        epoch: Int? = null
    ): Boolean {
        val revocation =
            try {
                json.decodeFromString<KeyRevocation>(decryptedContent)
            } catch (e: Exception) {
                Log.w(TAG, "Invalid key_revocation payload: ${e.message}")
                return false
            }

        // Ingestion verified the signature; bind its author to the retiring key and validate any successor.
        if (!revocation.isAuthorizedBy(authorPubkey)) {
            Log.w(
                TAG,
                "key_revocation signed by ${authorPubkey.take(8)} is not a valid retirement of " +
                    "${revocation.oldPubkey.take(8)} -> ${revocation.newPubkey.take(8)}, rejecting"
            )
            return false
        }

        if (epoch != null) {
            return groupRepo.applyAuthenticatedRevocation(
                groupId,
                revocation.oldPubkey,
                revocation.newPubkey,
                createdAt,
                eventId,
                epoch,
                successorProven = revocation.provesSuccessor(groupId)
            )
        }
        return groupRepo.applyIdentityRevocation(
            groupId,
            revocation.oldPubkey,
            revocation.newPubkey,
            createdAt,
            eventId,
            allowAbsent = own,
            successorProven = revocation.provesSuccessor(groupId)
        )
    }

    companion object {
        private const val TAG = "RevokeKeyUseCase"
    }
}
