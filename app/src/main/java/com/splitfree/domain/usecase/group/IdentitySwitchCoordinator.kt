package com.splitfree.domain.usecase.group

import com.splitfree.di.IoDispatcher
import com.splitfree.domain.repository.ControlOperation
import com.splitfree.domain.repository.ControlOperationJournalContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.IdentityState
import com.splitfree.domain.repository.IdentitySwitchTarget
import com.splitfree.domain.repository.SecureStorageKeyLostException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class IdentitySwitchCoordinator @Inject constructor(
    private val identity: IdentityContract,
    private val journal: ControlOperationJournalContract,
    private val operationLock: ControlOperationLock,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    suspend fun importKey(input: String) = switch(input)

    suspend fun generateKeyPair(): String = switch(null)

    suspend fun resumeIfNeeded() = withContext(ioDispatcher + NonCancellable) {
        operationLock.withLock { resumeLocked() }
    }

    suspend fun <T> withIdentity(expectedPubkey: String, block: () -> T): T = withContext(ioDispatcher) {
        operationLock.withLock {
            check(
                identity.identityState() == IdentityState.READY &&
                    identity.getPublicKeyHex() == expectedPubkey &&
                    identity.stagedIdentitySwitch() == null &&
                    journal.get(SWITCH_ID) == null
            ) {
                "Identity changed before the local operation completed"
            }
            block()
        }
    }

    private suspend fun switch(input: String?): String = withContext(ioDispatcher + NonCancellable) {
        operationLock.withLock {
            val state = identity.identityState()
            val existing = journal.get(SWITCH_ID)
            val interrupted = if (input == null) {
                try {
                    identity.stagedIdentitySwitch()?.newPubkey ?: existing?.let {
                        json.decodeFromString<IdentitySwitchTarget>(it.intentJson).newPubkey
                    }
                } catch (_: SecureStorageKeyLostException) {
                    null
                }
            } else {
                null
            }
            val superseded = try {
                if (existing != null &&
                    state != IdentityState.READY &&
                    state != IdentityState.UNAVAILABLE &&
                    identity.stagedIdentitySwitch() == null
                ) {
                    json.decodeFromString<IdentitySwitchTarget>(existing.intentJson).id
                } else {
                    resumeLocked()
                    null
                }
            } catch (_: SecureStorageKeyLostException) {
                existing?.let { json.decodeFromString<IdentitySwitchTarget>(it.intentJson).id }
            }
            if (interrupted != null && superseded == null && identity.getPublicKeyHex() == interrupted) {
                return@withLock interrupted
            }
            val target = identity.stageIdentitySwitch(input, superseded)
            finish(target)
            target.newPubkey
        }
    }

    private suspend fun resumeLocked() {
        val staged = identity.stagedIdentitySwitch()
        val operation = journal.get(SWITCH_ID)
        when {
            staged != null -> finish(staged)
            operation != null -> {
                val target = json.decodeFromString<IdentitySwitchTarget>(operation.intentJson)
                check(identity.getPublicKeyHex() == target.newPubkey) { "Staged identity unavailable" }
                reconcileIdentityOperations(identity, journal)
                journal.complete(SWITCH_ID)
            }
            identity.identityState() == IdentityState.READY -> reconcileIdentityOperations(identity, journal)
        }
    }

    private suspend fun finish(target: IdentitySwitchTarget) {
        val operation = ControlOperation(SWITCH_ID, SWITCH_KIND, json.encodeToString(target))
        var existing = journal.get(SWITCH_ID)
        if (existing != null && existing != operation && target.supersededSwitchId != null) {
            check(json.decodeFromString<IdentitySwitchTarget>(existing.intentJson).id == target.supersededSwitchId) {
                "Superseded identity switch changed"
            }
            journal.move(existing, "archive:${target.supersededSwitchId}:$SWITCH_ID", "archived:identity-switch")
            existing = null
        }
        if (existing == null) journal.insert(operation) else check(existing == operation) { "Identity switch changed" }
        // Keep the secure stage and journal until installation, readback and reconciliation finish.
        // A write can land before reporting failure; retry the staged target instead of rolling back.
        identity.commitIdentitySwitch(target)
        if (target.oldPubkey != null &&
            target.oldPubkey != target.newPubkey &&
            target.pendingPubkey != null &&
            identity.getPendingPublicKeyHex() == target.pendingPubkey
        ) {
            identity.archivePendingKeyPair(target.oldPubkey)
        }
        reconcileIdentityOperations(identity, journal)
        identity.completeIdentitySwitch(target)
        journal.complete(SWITCH_ID)
    }

    companion object {
        const val SWITCH_ID = "identity-switch"
        private const val SWITCH_KIND = "identity-switch"
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/** Called only under ControlOperationLock, never from a Room transaction or the main thread. */
internal suspend fun reconcileIdentityOperations(identity: IdentityContract, journal: ControlOperationJournalContract) {
    val active = identity.getPublicKeyHex()
    if (active.isEmpty()) return
    val json = Json { ignoreUnknownKeys = true }
    for (operation in journal.getAll("revocation")) {
        val intent = json.decodeFromString<RevocationIntent>(operation.intentJson)
        val prepared = operation.preparedJson?.let { json.decodeFromString<PreparedRevocation>(it) }
        if (active == intent.oldPubkey || active == prepared?.newPubkey) continue
        if (prepared != null && identity.getPendingPublicKeyHex() == prepared.newPubkey) {
            identity.archivePendingKeyPair(intent.oldPubkey)
        }
        journal.move(operation, "archive:${UUID.randomUUID()}:${operation.id}", "archived:revocation")
    }
    for (operation in journal.getAll("rotation")) {
        val intent = json.decodeFromString<RotationIntent>(operation.intentJson)
        if (active != intent.group.createdBy) {
            journal.move(operation, "archive:${UUID.randomUUID()}:${operation.id}", "archived:rotation")
        }
    }
    for (operation in journal.getAll("archived:revocation")) {
        val intent = json.decodeFromString<RevocationIntent>(operation.intentJson)
        val prepared = operation.preparedJson?.let { json.decodeFromString<PreparedRevocation>(it) }
        if (active != intent.oldPubkey && active != prepared?.newPubkey) continue
        if (journal.get(RotateGroupKeyUseCase.REVOCATION_ID) != null) continue
        if (active == intent.oldPubkey) identity.restoreArchivedPendingKeyPair(intent.oldPubkey, prepared?.newPubkey)
        journal.move(operation, RotateGroupKeyUseCase.REVOCATION_ID, "revocation")
    }
    for (operation in journal.getAll("archived:rotation")) {
        val intent = json.decodeFromString<RotationIntent>(operation.intentJson)
        val id = "rotation:${intent.group.id}"
        if (active == intent.group.createdBy && journal.get(id) == null) journal.move(operation, id, "rotation")
    }
}
