package com.splitfree.sync.event

import com.splitfree.di.ApplicationScope
import com.splitfree.domain.model.group.GroupIdentity
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.usecase.group.RevokeKeyUseCase
import com.splitfree.domain.usecase.group.RotateGroupKeyUseCase
import com.splitfree.domain.usecase.sync.SelfHealUseCase
import com.splitfree.util.DebugLog as Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Handles post-storage side effects for `group_meta`, `key_rotation`, and `key_revocation` events.
 */
@Singleton
class EventPostProcessor
@Inject
constructor(
    private val groupRepo: GroupRepositoryContract,
    private val rotateGroupKey: RotateGroupKeyUseCase,
    private val revokeKey: RevokeKeyUseCase,
    private val selfHeal: SelfHealUseCase,
    private val eventPublisher: EventPublisherContract,
    private val identity: IdentityContract,
    @ApplicationScope private val appScope: CoroutineScope
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Process a post-storage side effect.
     *
     * @param eventType one of `group_meta`, `key_rotation`, `key_revocation`
     * @param decrypted decrypted event content JSON, or null to skip
     * @param authorHex pubkey of the event author
     * @param groupId target group UUID
     * @param createdAt event timestamp (unix seconds)
     * @param nonCancellable if true, runs inside [NonCancellable] context
     */
    suspend fun handle(
        eventType: String,
        decrypted: String?,
        authorHex: String,
        groupId: String,
        createdAt: Long,
        nonCancellable: Boolean
    ) {
        if (decrypted == null) return

        when (eventType) {
            "group_meta" -> handleGroupMeta(decrypted, authorHex, groupId, createdAt, nonCancellable)
            "key_rotation" -> runSafe(nonCancellable, "key_rotation", groupId) {
                rotateGroupKey.handleKeyRotation(decrypted, authorHex, groupId, createdAt)
            }
            "key_revocation" -> runSafe(nonCancellable, "key_revocation", groupId) {
                revokeKey.handleRevocation(decrypted, authorHex, groupId)
            }
        }
    }

    private suspend fun handleGroupMeta(
        decrypted: String,
        authorHex: String,
        groupId: String,
        createdAt: Long,
        nonCancellable: Boolean
    ) {
        runSafe(nonCancellable, "group_meta", groupId) {
            val meta = json.decodeFromString<GroupMeta>(decrypted)
            if (meta.members.isEmpty()) return@runSafe

            val currentGroup = groupRepo.getById(groupId)
            val isKnownCreator =
                currentGroup != null &&
                    currentGroup.createdBy.isNotEmpty() &&
                    authorHex == currentGroup.createdBy
            // Legacy/imported groups have no creator on record. The only author allowed to fill
            // that gap is the one the group id was derived from, a claim anyone can verify, so a
            // non-creator cannot promote themselves by publishing a group_meta.
            val bootstrapsCreator =
                currentGroup != null &&
                    currentGroup.createdBy.isEmpty() &&
                    meta.createdBy == authorHex &&
                    GroupIdentity.matches(groupId, authorHex, meta.createdAt)
            val isCreator = currentGroup == null || isKnownCreator || bootstrapsCreator

            val finalMembers =
                if (isCreator) {
                    meta.members
                } else {
                    ((currentGroup?.members ?: emptyList()) + authorHex).distinct()
                }
            val finalName = if (isCreator) meta.name else (currentGroup?.name ?: meta.name)
            val finalRelays = if (isCreator) meta.relays else (currentGroup?.relays ?: meta.relays)
            val finalMemberNames =
                if (isCreator) {
                    meta.memberNames
                } else {
                    // Non-creator events may only contribute their own display name.
                    (currentGroup?.memberNames ?: emptyMap()).toMutableMap().apply {
                        val authorName = meta.memberNames[authorHex]?.trim().orEmpty().take(50)
                        if (authorName.isNotEmpty()) {
                            put(authorHex, authorName)
                        } else {
                            remove(authorHex)
                        }
                    }
                }
            val trustedCreatedBy =
                when {
                    isKnownCreator -> meta.createdBy.ifEmpty { authorHex }
                    bootstrapsCreator -> authorHex
                    else -> ""
                }
            val relaysChanged = currentGroup != null && currentGroup.relays.toSet() != finalRelays.toSet()

            if (bootstrapsCreator) {
                Log.i(TAG, "Adopting verified creator ${authorHex.take(8)} for legacy group $groupId")
                // Independent of the LWW watermark: the binding is cryptographic, not chronological.
                groupRepo.updateCreator(groupId, authorHex, meta.createdAt)
            }

            Log.i(TAG, "Applying group_meta for $groupId: ${finalMembers.size} members, name=$finalName")
            groupRepo.updateFromMeta(
                groupId,
                finalName,
                finalMembers,
                finalRelays,
                createdAt,
                trustedCreatedBy,
                finalMemberNames
            )

            if (relaysChanged) {
                Log.i(TAG, "Relays changed for $groupId — triggering eager self-heal")
                appScope.launch {
                    try {
                        selfHeal(groupId)
                    } catch (
                        e: Exception
                    ) {
                        Log.w(TAG, "Eager self-heal failed for $groupId: ${e.message}")
                    }
                }
            }

            // Members that just appeared could not decrypt any gift wrap I published before now,
            // so re-deliver my history to them. This also covers the creator's solo period: the
            // expenses added before anyone joined produced zero deliveries, and the first member's
            // self-join group_meta lands here with newMembers = {them}.
            //
            // Diff the PERSISTED member list, not `finalMembers`: updateFromMeta is LWW-guarded, and
            // a stale meta replayed from a relay may still list someone removed by a later rotation.
            // Wrapping my history for them would hand it to a non-member.
            val persistedMembers = groupRepo.getById(groupId)?.members?.toSet() ?: emptySet()
            val newMembers =
                persistedMembers - (currentGroup?.members?.toSet() ?: emptySet()) - identity.getPublicKeyHex()
            if (newMembers.isNotEmpty()) {
                Log.i(TAG, "${newMembers.size} new member(s) in $groupId, re-delivering authored history")
                appScope.launch {
                    try {
                        eventPublisher.redeliverAuthoredEvents(groupId, newMembers)
                    } catch (
                        e: Exception
                    ) {
                        Log.w(TAG, "Re-delivery to new members failed for $groupId: ${e.message}")
                    }
                }
            }
        }
    }

    private suspend inline fun runSafe(
        nonCancellable: Boolean,
        eventType: String,
        groupId: String,
        crossinline block: suspend () -> Unit
    ) {
        try {
            if (nonCancellable) withContext(NonCancellable) { block() } else block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process $eventType for $groupId: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "EventPostProcessor"
    }
}
