package com.splitfree.domain.usecase.group

import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.model.group.KeyRevocation
import com.splitfree.domain.repository.ControlOperation
import com.splitfree.domain.repository.ControlOperationJournalContract
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.util.DebugLog as Log
import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A durable intent owns its pending identity and every old-key-signed event until projection and promotion finish.
 *
 * Everything that can refuse a revocation is checked before the intent is journaled; once journaled it
 * can always complete: the local projection tolerates a roster that dropped this user meanwhile (the
 * tombstone is still recorded, nobody is re-added) and a group deleted locally.
 */
class RevokeKeyUseCase
@Inject
constructor(
    private val identity: IdentityContract,
    private val groupRepo: GroupRepositoryContract,
    private val encryption: GroupEncryption,
    private val signer: EventSigner,
    private val eventPublisher: EventPublisherContract,
    private val journal: ControlOperationJournalContract,
    private val operationLock: ControlOperationLock
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend operator fun invoke(): String = withContext(NonCancellable) {
        operationLock.withLock {
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
                checkNotNull(groupRepo.getGroupKeyForEpoch(group.id, group.keyEpoch)) {
                    "Current-epoch key unavailable for group ${group.id}; revocation not started"
                }
            }
            val operation = ControlOperation(
                RotateGroupKeyUseCase.REVOCATION_ID,
                "revocation",
                json.encodeToString(intent)
            )
            // Room is written first. After a crash even a missing pending key is unambiguously pre-publication.
            journal.insert(operation)
            finish(operation)
        }
    }

    suspend fun resumeIfNeeded() {
        withContext(NonCancellable) {
            operationLock.withLock {
                val operation = journal.get(RotateGroupKeyUseCase.REVOCATION_ID)
                if (operation != null) {
                    finish(operation)
                } else if (identity.hasPendingKeyPair()) {
                    // Old builds wrote IDs only AFTER publishing: empty IDs/age/outbox absence prove nothing.
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
            val events = intent.groups.flatMap { group ->
                val key = checkNotNull(groupRepo.getGroupKeyForEpoch(group.id, group.keyEpoch)) {
                    "Current-epoch key unavailable for group ${group.id}; revocation not published"
                }
                prepareRevocation(group, key, intent.oldPubkey, newPubkey)
            }
            json.encodeToString(PreparedRevocation(newPubkey, events)).also { journal.prepare(operation.id, it) }
        }
        val prepared = json.decodeFromString<PreparedRevocation>(preparedJson)
        check(identity.getPublicKeyHex() == intent.oldPubkey || identity.getPublicKeyHex() == prepared.newPubkey) {
            "Revocation belongs to a different identity"
        }
        check(
            identity.getPublicKeyHex() == prepared.newPubkey || identity.getPendingPublicKeyHex() == prepared.newPubkey
        ) {
            "Journaled replacement identity unavailable"
        }
        // Tracking IDs are compatibility state, not proof of publication or authorization to discard a key.
        identity.setRevocationEventIds(prepared.events.map { it.event().id })
        prepared.events.filter { it.eventType == "key_revocation" }.forEach { it.publish(eventPublisher) }
        prepared.events.filter { it.eventType == "group_meta" }.forEach { it.publish(eventPublisher) }
        val payload = json.encodeToString(KeyRevocation(intent.oldPubkey, prepared.newPubkey, "Key compromised"))
        for (revocation in prepared.events.filter { it.eventType == "key_revocation" }) {
            // Ordered by the revocation event's own clock, exactly as every receiver orders it.
            val event = revocation.event()
            val projected =
                applyRevocation(payload, intent.oldPubkey, revocation.groupId, event.createdAt, event.id, own = true)
            check(projected || groupRepo.getById(revocation.groupId) == null) {
                "Could not project revocation for group ${revocation.groupId}"
            }
        }
        identity.finishPendingKeyPair(prepared.newPubkey)
        journal.complete(operation.id)
        return prepared.newPubkey
    }

    private fun prepareRevocation(
        group: Group,
        groupKey: String,
        oldPubkey: String,
        newPubkey: String
    ): List<PreparedControlEvent> {
        val names = group.memberNames.toMutableMap().apply {
            val oldName = remove(oldPubkey)
            if (oldName != null && newPubkey !in this) put(newPubkey, oldName)
        }
        val meta = GroupMeta(
            group.name,
            group.description,
            if (group.createdBy == oldPubkey) newPubkey else group.createdBy,
            group.createdAt,
            group.members.map { if (it == oldPubkey) newPubkey else it }.distinct(),
            group.relays,
            names,
            keyEpoch = group.keyEpoch
        )
        val payloads = listOf(
            "key_revocation" to json.encodeToString(KeyRevocation(oldPubkey, newPubkey, "Key compromised")),
            "group_meta" to json.encodeToString(meta)
        )
        return payloads.map { (type, payload) ->
            val encrypted = encryption.encrypt(payload, groupKey)
            PreparedControlEvent(group.id, type, signer.createSignedEvent(group.id, type, encrypted).toJson())
        }
    }

    /**
     * Handle an incoming key_revocation event from another member.
     * Replace their old pubkey with the new one in the group member list.
     *
     * @param createdAt the revocation event's `created_at`; the creator watermark is raised to it so a
     *   `group_meta` authored before the revocation cannot re-add the revoked key
     * @param eventId the revocation event's id (watermark tiebreak)
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
        own: Boolean
    ): Boolean {
        val revocation =
            try {
                json.decodeFromString<KeyRevocation>(decryptedContent)
            } catch (e: Exception) {
                Log.w(TAG, "Invalid key_revocation payload: ${e.message}")
                return false
            }

        // The event must be signed by the old key being revoked
        if (authorPubkey != revocation.oldPubkey) {
            Log.w(TAG, "key_revocation signed by $authorPubkey but claims to revoke ${revocation.oldPubkey}")
            return false
        }

        val newPubkey = revocation.newPubkey
        if (newPubkey.isNotEmpty() && !PUBKEY_HEX.matches(newPubkey)) {
            Log.w(TAG, "key_revocation from ${authorPubkey.take(8)} carries a malformed new pubkey, rejecting")
            return false
        }
        if (newPubkey == revocation.oldPubkey) {
            Log.w(TAG, "key_revocation from ${authorPubkey.take(8)} names itself as the new key, rejecting")
            return false
        }

        return groupRepo.applyIdentityRevocation(
            groupId,
            revocation.oldPubkey,
            newPubkey,
            createdAt,
            eventId,
            allowAbsent = own
        )
    }

    companion object {
        private const val TAG = "RevokeKeyUseCase"
        private val PUBKEY_HEX = Regex("^[0-9a-f]{64}$")
    }
}
