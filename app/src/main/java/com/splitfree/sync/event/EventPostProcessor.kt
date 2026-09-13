package com.splitfree.sync.event

import com.splitfree.di.ApplicationScope
import com.splitfree.domain.model.group.Group
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
                when (rotateGroupKey.handleKeyRotation(decrypted, authorHex, groupId, createdAt)) {
                    RotationOutcome.APPLIED -> PostProcessOutcome.APPLIED
                    RotationOutcome.DEFERRED_EPOCH_GAP,
                    RotationOutcome.DEFERRED_MEMBERSHIP -> PostProcessOutcome.DEFERRED
                    // Already at or past this epoch: a harmless replay, nothing left to retry.
                    RotationOutcome.IGNORED -> PostProcessOutcome.APPLIED
                    RotationOutcome.REJECTED -> PostProcessOutcome.REJECTED
                }
            }
            "key_revocation" -> runSafe(nonCancellable, "key_revocation", groupId) {
                revokeKey.handleRevocation(decrypted, authorHex, groupId, createdAt, eventId)
                PostProcessOutcome.APPLIED
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
        val meta = json.decodeFromString<GroupMeta>(decrypted)
        if (meta.members.isEmpty()) return@runSafe PostProcessOutcome.APPLIED

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

        if (isCreator) {
            applyCreatorMeta(
                meta,
                authorHex,
                groupId,
                createdAt,
                eventId,
                currentGroup,
                isKnownCreator,
                bootstrapsCreator,
                applyRoster = currentGroup == null || keyEpoch >= currentGroup.keyEpoch
            )
        } else {
            applyMemberSelfMeta(meta, authorHex, groupId, createdAt, eventId, checkNotNull(currentGroup))
        }

        // Members that just appeared could not decrypt any gift wrap I published before now,
        // so re-deliver my history to them. This also covers the creator's solo period: the
        // expenses added before anyone joined produced zero deliveries, and the first member's
        // self-join group_meta lands here with newMembers = {them}.
        //
        // Diff the PERSISTED member list, not the meta's: both write paths are ordered (LWW
        // watermark for the creator, per-member clock for self-updates), and a stale meta replayed
        // from a relay may still list someone removed by a later rotation. Wrapping my history for
        // them would hand it to a non-member.
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
        PostProcessOutcome.APPLIED
    }

    /**
     * The creator's (or a bootstrapping / first-seen) meta is authoritative for the group: name,
     * relays, names and description come from the payload, ordered by the LWW watermark
     * `(createdAt, eventId)` inside [GroupRepositoryContract.updateFromMeta]. The roster is taken only
     * when [applyRoster]: a meta sealed under an older key epoch predates a rotation and must not
     * re-add whoever that rotation removed (or drop whoever it kept).
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
        applyRoster: Boolean
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
            applyRoster = applyRoster
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
     * A non-creator's meta may only speak for its author: joining the group and setting their own
     * display name. It goes through [GroupRepositoryContract.applyMemberSelfUpdate], which orders it
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
        currentGroup: Group
    ) {
        val join = authorHex !in currentGroup.members
        Log.i(TAG, "Applying member self-update from ${authorHex.take(8)} for $groupId (join=$join)")
        val applied = groupRepo.applyMemberSelfUpdate(
            groupId,
            authorHex,
            createdAt,
            eventId,
            join = join,
            displayName = meta.memberNames[authorHex]
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
