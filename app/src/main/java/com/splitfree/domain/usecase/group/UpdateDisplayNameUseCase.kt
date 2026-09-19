package com.splitfree.domain.usecase.group

import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.repository.ControlOperationJournalContract
import com.splitfree.domain.repository.DisplayNameDelivery
import com.splitfree.domain.repository.DisplayNameGroupChangedException
import com.splitfree.domain.repository.DisplayNameIntent
import com.splitfree.domain.repository.DisplayNamePublishResult
import com.splitfree.domain.repository.DisplayNameRepositoryContract
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.PreparedDisplayName
import com.splitfree.domain.repository.SettingsContract
import com.splitfree.domain.repository.groupMeta
import com.splitfree.util.DebugLog as Log
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

class UpdateDisplayNameUseCase @Inject constructor(
    private val groupRepo: GroupRepositoryContract,
    private val identity: IdentityContract,
    private val encryption: GroupEncryption,
    private val signer: EventSigner,
    private val eventPublisher: EventPublisherContract,
    private val settings: SettingsContract,
    private val names: DisplayNameRepositoryContract,
    private val controlLock: ControlOperationLock,
    private val journal: ControlOperationJournalContract
) {
    suspend operator fun invoke(intent: DisplayNameIntent): DisplayNamePublishResult {
        val delivered = linkedMapOf<String, DisplayNameDelivery>()
        val deferred = linkedSetOf<String>()
        if (!isCurrent(intent)) return DisplayNamePublishResult(superseded = true)
        names.saveIntent(intent)
        for (group in groupRepo.getAll()) {
            try {
                val result = controlLock.withLock {
                    if (!isCurrent(intent)) return@withLock null
                    val current = groupRepo.getById(group.id) ?: return@withLock GroupResult.Removed
                    if (intent.identityPubkey !in current.members) return@withLock GroupResult.Removed
                    if (identity.hasPendingKeyPair() ||
                        identity.stagedIdentitySwitch() != null ||
                        journal.get(IdentitySwitchCoordinator.SWITCH_ID) != null
                    ) {
                        return@withLock GroupResult.Deferred
                    }
                    GroupResult.Delivered(publishGroup(intent, current) ?: return@withLock null)
                }
                when (result) {
                    null -> return DisplayNamePublishResult(delivered, deferred, superseded = true)
                    GroupResult.Removed -> Unit
                    GroupResult.Deferred -> deferred += group.id
                    is GroupResult.Delivered -> delivered[group.id] = result.delivery
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                deferred += group.id
                Log.w(TAG, "Name publication deferred: ${e.javaClass.simpleName}")
            }
        }
        return DisplayNamePublishResult(delivered, deferred)
    }

    private fun isCurrent(intent: DisplayNameIntent): Boolean = identity.hasIdentity() &&
        identity.getPublicKeyHex() == intent.identityPubkey &&
        settings.getDisplayNameIntent(intent.identityPubkey)?.revision == intent.revision

    private suspend fun publishGroup(intent: DisplayNameIntent, group: Group): DisplayNameDelivery? {
        val committed = names.committedEventId(intent, group.id)
        if (committed != null && group.memberNames[intent.identityPubkey].orEmpty() == intent.name) {
            return DisplayNameDelivery.DURABLY_QUEUED
        }
        val prepared = if (committed == null) names.getPrepared(intent, group.id) else null
        val operation = prepared ?: prepare(intent, group)
        return try {
            eventPublisher.publishDisplayName(operation)
        } catch (e: DisplayNameGroupChangedException) {
            names.discardPrepared(intent, group.id)
            throw e
        }
    }

    private suspend fun prepare(intent: DisplayNameIntent, group: Group): PreparedDisplayName {
        val key = groupRepo.getGroupKeyForEpoch(group.id, group.keyEpoch) ?: error("Group key unavailable")
        val timestamp = names.reserveTimestamp(
            intent,
            group.id,
            groupRepo.nameClockFloor(group.id, intent.identityPubkey),
            System.currentTimeMillis() / 1000
        )
        val meta = intent.groupMeta(group)
        val encrypted = encryption.encrypt(Json.encodeToString(GroupMeta.serializer(), meta), key)
        val event = signer.createSignedEvent(
            groupId = group.id,
            eventType = "group_meta",
            encryptedContent = encrypted,
            createdAt = timestamp
        )
        check(event.pubkey == intent.identityPubkey && event.createdAt == timestamp) {
            "Signing identity or clock changed"
        }
        return PreparedDisplayName(intent, group, event).also { names.savePrepared(it) }
    }

    private sealed interface GroupResult {
        data object Removed : GroupResult
        data object Deferred : GroupResult
        data class Delivered(val delivery: DisplayNameDelivery) : GroupResult
    }

    companion object {
        private const val TAG = "UpdateDisplayName"
    }
}
