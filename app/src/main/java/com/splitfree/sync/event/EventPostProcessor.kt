package com.splitfree.sync.event

import com.splitfree.di.ApplicationScope
import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupIdentity
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.model.group.KeyRevocation
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
 * Handles post-storage side effects for `group_meta`, `key_rotation`, and `key_revocation` events and
 * reports whether they landed as a [PostProcessOutcome], so the caller can keep a row pending
 * ([PostProcessOutcome.DEFERRED]) and retry it once its dependency arrives.
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
     * @param eventType one of `group_meta`, `key_rotation`, `key_revocation`; any other type has no side
     *   effects and is reported as [PostProcessOutcome.APPLIED]
     * @param decrypted decrypted event content JSON; null yields [PostProcessOutcome.FAILED]
     * @param authorHex pubkey of the event author
     * @param groupId target group UUID
     * @param createdAt event timestamp (unix seconds)
     * @param nonCancellable if true, runs inside [NonCancellable] context
     * @param eventId id of the event being applied; threaded into the metadata watermark and the
     *   per-member clock so equal timestamps are broken identically on every device
     * @param keyEpoch the key epoch the content decrypted under. A creator `group_meta` sealed under an
     *   epoch older than the group's current one predates a rotation and may not change the roster.
     * @return whether the side effect landed, must be retried later, failed transiently (retried) or
     *   was permanently rejected; exceptions other than [kotlinx.coroutines.CancellationException] are
     *   logged and reported as [PostProcessOutcome.FAILED]
     */
    suspend fun handle(
        eventType: String,
        decrypted: String?,
        authorHex: String,
        groupId: String,
        createdAt: Long,
        nonCancellable: Boolean,
        eventId: String = "",
        keyEpoch: Int = Int.MAX_VALUE
    ): PostProcessOutcome {
        if (decrypted == null) return PostProcessOutcome.FAILED

        return when (eventType) {
            "group_meta" ->
                handleGroupMeta(decrypted, authorHex, groupId, createdAt, nonCancellable, eventId, keyEpoch)
            "key_rotation" -> runSafe(nonCancellable, "key_rotation", groupId) {
                when (rotateGroupKey.handleKeyRotation(decrypted, authorHex, groupId, createdAt, eventId)) {
                    RotationOutcome.APPLIED -> PostProcessOutcome.APPLIED
                    RotationOutcome.DEFERRED_EPOCH_GAP,
                    RotationOutcome.DEFERRED_MEMBERSHIP -> PostProcessOutcome.DEFERRED
                    // Already at or past this epoch: a harmless replay, nothing left to retry.
                    RotationOutcome.IGNORED -> PostProcessOutcome.APPLIED
                    RotationOutcome.REJECTED -> PostProcessOutcome.REJECTED
                }
            }
            "key_revocation" -> runSafe(nonCancellable, "key_revocation", groupId) {
                val applied = if (groupRepo.hasCanonicalProjection(groupId)) {
                    val revocation = json.decodeFromString<KeyRevocation>(decrypted)
                    revocation.isAuthorizedBy(authorHex) &&
                        groupRepo.applyAuthenticatedRevocation(
                            groupId,
                            revocation.oldPubkey,
                            revocation.newPubkey,
                            createdAt,
                            eventId,
                            keyEpoch.takeUnless { it == Int.MAX_VALUE } ?: (groupRepo.getById(groupId)?.keyEpoch ?: 0),
                            revocation.provesSuccessor(groupId)
                        )
                } else {
                    revokeKey.handleRevocation(decrypted, authorHex, groupId, createdAt, eventId)
                }
                if (applied) {
                    PostProcessOutcome.APPLIED
                } else {
                    PostProcessOutcome.REJECTED
                }
            }
            else -> PostProcessOutcome.APPLIED
        }
    }

    private suspend fun handleGroupMeta(
        decrypted: String,
        authorHex: String,
        groupId: String,
        createdAt: Long,
        nonCancellable: Boolean,
        eventId: String,
        keyEpoch: Int
    ): PostProcessOutcome = runSafe(nonCancellable, "group_meta", groupId) {
        val meta = try {
            json.decodeFromString<GroupMeta>(decrypted)
        } catch (_: kotlinx.serialization.SerializationException) {
            return@runSafe PostProcessOutcome.REJECTED
        } catch (_: IllegalArgumentException) {
            return@runSafe PostProcessOutcome.REJECTED
        }
        if (meta.members.isEmpty()) return@runSafe PostProcessOutcome.REJECTED

        val storedGroup = groupRepo.getById(groupId)
        val currentGroup = if (meta.creatorTransitions.isNotEmpty()) {
            groupRepo.previewCreatorBootstrap(groupId, meta.originalCreator, meta.createdAt, meta.creatorTransitions)
                ?: return@runSafe PostProcessOutcome.REJECTED
        } else {
            storedGroup
        }
        val isKnownCreator =
            currentGroup != null &&
                currentGroup.createdBy.isNotEmpty() &&
                authorHex == currentGroup.createdBy
        // A missing creator binding requires the original creator's signature and a matching
        // deterministic group id; self-declaring createdBy is not enough.
        val bootstrapsCreator =
            currentGroup != null &&
                currentGroup.createdBy.isEmpty() &&
                meta.createdBy == authorHex &&
                GroupIdentity.matches(groupId, authorHex, meta.createdAt)
        val isCreator = currentGroup == null || isKnownCreator || bootstrapsCreator

        val canonical = groupRepo.hasCanonicalProjection(groupId)
        val hasCreatorAuthority = if (canonical) {
            isKnownCreator ||
                (bootstrapsCreator && groupRepo.resolveRoster(groupId, listOf(authorHex)) == listOf(authorHex)) ||
                (
                    currentGroup != null &&
                        groupRepo.isHistoricalCreatorMeta(currentGroup, meta, authorHex, createdAt, eventId)
                    )
        } else {
            isCreator
        }
        // A retired author may relay its portable certificate in the journaled companion. This
        // exempts only transport admission; the reducer still refuses post-retirement metadata writes.
        val proofCarrier = meta.creatorTransitions.any { it.oldPubkey == authorHex && it.newPubkey == meta.createdBy }
        if (currentGroup != null && !hasCreatorAuthority && !proofCarrier) {
            // A retained old epoch does not authorize rejoining the current roster. Recheck this
            // when retrying metadata that was admitted before the author was removed.
            if (authorHex !in currentGroup.members &&
                (
                    keyEpoch != Int.MAX_VALUE &&
                        keyEpoch != currentGroup.keyEpoch ||
                        authorHex !in meta.members ||
                        (meta.members.toSet() - currentGroup.members.toSet() - authorHex).isNotEmpty()
                    )
            ) {
                return@runSafe PostProcessOutcome.REJECTED
            }
            // Reject rather than mark a tombstoned self-join applied: applied rows are offered to peers.
            if (authorHex !in currentGroup.members &&
                groupRepo.resolveRoster(groupId, listOf(authorHex)) != listOf(authorHex)
            ) {
                Log.w(TAG, "Rejecting self-join of revoked identity ${authorHex.take(8)} in $groupId")
                return@runSafe PostProcessOutcome.REJECTED
            }
        }
        if (canonical) {
            if (!groupRepo.applyAuthenticatedMeta(
                    groupId,
                    meta,
                    authorHex,
                    createdAt,
                    eventId,
                    keyEpoch.takeUnless { it == Int.MAX_VALUE } ?: (currentGroup?.keyEpoch ?: 0)
                )
            ) {
                return@runSafe PostProcessOutcome.REJECTED
            }
        } else if (isCreator) {
            if (!InviteLinkCodec.fitsInviteLink(meta.relays.filter(InviteLinkCodec::relayFits))) {
                return@runSafe PostProcessOutcome.REJECTED
            }
            applyCreatorMeta(
                meta,
                authorHex,
                groupId,
                createdAt,
                eventId,
                currentGroup,
                isKnownCreator,
                bootstrapsCreator,
                applyRoster = currentGroup == null || keyEpoch >= currentGroup.keyEpoch,
                expectedKeyEpoch = keyEpoch.takeUnless { it == Int.MAX_VALUE }
            )
        } else {
            val group = checkNotNull(currentGroup)
            applyMemberSelfMeta(
                meta,
                authorHex,
                groupId,
                createdAt,
                eventId,
                group,
                keyEpoch.takeUnless { it == Int.MAX_VALUE }
            )
        }

        // Use the persisted projection for relay migration and membership changes, not the payload:
        // stale metadata may name removed members. Newly persisted members need fresh gift wraps of
        // authored history, including expenses created before the group had any other recipients.
        val persisted = groupRepo.getById(groupId)
        if (canonical &&
            hasCreatorAuthority &&
            currentGroup != null &&
            persisted != null &&
            currentGroup.relays.toSet() != persisted.relays.toSet()
        ) {
            appScope.launch {
                try {
                    selfHeal(groupId)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Relay migration deferred for $groupId: ${e.message}")
                }
            }
        }
        val persistedMembers = persisted?.members?.toSet() ?: emptySet()
        val newMembers =
            persistedMembers - (storedGroup?.members?.toSet() ?: emptySet()) - identity.getPublicKeyHex()
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
        PostProcessOutcome.APPLIED
    }

    /**
     * Legacy metadata path; canonical groups use the authenticated reducer instead.
     *
     * - [GroupRepositoryContract.updateFromMeta] orders creator fields by `(createdAt, eventId)`;
     *   a member's newer self-rename retains precedence over the creator's name map.
     * - [applyRoster] and the repository's epoch guard prevent old-key metadata from undoing a rotation.
     */
    private suspend fun applyCreatorMeta(
        meta: GroupMeta,
        authorHex: String,
        groupId: String,
        createdAt: Long,
        eventId: String,
        currentGroup: Group?,
        isKnownCreator: Boolean,
        bootstrapsCreator: Boolean,
        applyRoster: Boolean,
        expectedKeyEpoch: Int?
    ) {
        val trustedCreatedBy =
            when {
                isKnownCreator -> meta.createdBy.ifEmpty { authorHex }
                bootstrapsCreator -> authorHex
                else -> ""
            }
        val relaysChanged = currentGroup != null && currentGroup.relays.toSet() != meta.relays.toSet()

        if (bootstrapsCreator) {
            Log.i(TAG, "Adopting verified creator ${authorHex.take(8)} for legacy group $groupId")
            // Independent of the LWW watermark: the binding is cryptographic, not chronological.
            groupRepo.updateCreator(groupId, authorHex, meta.createdAt)
        }

        Log.i(TAG, "Applying group_meta for $groupId: ${meta.members.size} members, name=${meta.name}")
        groupRepo.updateFromMeta(
            groupId,
            meta.name,
            meta.members,
            meta.relays,
            createdAt,
            trustedCreatedBy,
            meta.memberNames,
            // Only the creator's meta is authoritative for the description.
            description = meta.description,
            eventId = eventId,
            applyRoster = applyRoster,
            expectedKeyEpoch = expectedKeyEpoch,
            expectedCreator = if (isKnownCreator || bootstrapsCreator) authorHex else currentGroup?.createdBy
        )

        if (relaysChanged) {
            Log.i(TAG, "Relays changed for $groupId, triggering eager self-heal")
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
    }

    /**
     * Legacy non-creator metadata may only join its author and update their display name.
     * [GroupRepositoryContract.applyMemberSelfUpdate] orders it
     * by the author's own `(createdAt, eventId)` clock and never advances the creator's watermark, so a
     * member's future-dated rename can no longer block an older-but-authoritative creator meta.
     *
     * The author's name is taken from `member_names[author]`: absent leaves the stored name alone,
     * empty clears it. Name, relays, description and other members' names in the payload are ignored.
     */
    private suspend fun applyMemberSelfMeta(
        meta: GroupMeta,
        authorHex: String,
        groupId: String,
        createdAt: Long,
        eventId: String,
        currentGroup: Group,
        expectedKeyEpoch: Int?
    ) {
        val join = authorHex !in currentGroup.members
        Log.i(TAG, "Applying member self-update from ${authorHex.take(8)} for $groupId (join=$join)")
        val applied = groupRepo.applyMemberSelfUpdate(
            groupId,
            authorHex,
            createdAt,
            eventId,
            join = join,
            displayName = meta.memberNames[authorHex],
            expectedKeyEpoch = expectedKeyEpoch
        )
        if (!applied) Log.i(TAG, "Member self-update from ${authorHex.take(8)} for $groupId was stale, ignored")
    }

    /**
     * Runs [block] (inside [NonCancellable] when requested) and maps any failure to
     * [PostProcessOutcome.FAILED]. Cancellation is never swallowed.
     */
    private suspend inline fun runSafe(
        nonCancellable: Boolean,
        eventType: String,
        groupId: String,
        crossinline block: suspend () -> PostProcessOutcome
    ): PostProcessOutcome = try {
        if (nonCancellable) withContext(NonCancellable) { block() } else block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Failed to process $eventType for $groupId: ${e.message}", e)
        PostProcessOutcome.FAILED
    }

    companion object {
        private const val TAG = "EventPostProcessor"
    }
}

internal suspend fun GroupRepositoryContract.isHistoricalCreatorMeta(
    group: Group,
    meta: GroupMeta,
    author: String,
    timestamp: Long,
    eventId: String
): Boolean {
    // An old key's rootless self-join must not masquerade as creator history by backdating its event.
    if (meta.createdAt <= 0 || meta.createdAt != group.createdAt || meta.createdBy.isEmpty()) return false
    if (!isHistoricalCreator(group.id, author, timestamp, eventId)) return false
    if (meta.createdBy == author) return true
    val retired = retiredIdentities(group.id)
    return author in retired.revoked && retired.successors[author] == meta.createdBy
}
