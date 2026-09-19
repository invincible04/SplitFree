package com.splitfree.sync.event

import com.splitfree.data.local.dao.EventDao
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GiftWrapService
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.NostrKind
import com.splitfree.domain.crypto.nip.Nip44
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.Settlement
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.model.group.IdentityHistory
import com.splitfree.domain.model.group.IdentityHistoryPage
import com.splitfree.domain.model.group.KeyRevocation
import com.splitfree.domain.repository.EventSnapshot
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.util.hexToBytes
import com.splitfree.domain.validation.EventValidator
import com.splitfree.util.DebugLog as Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Event processing pipeline: unwrap → validate → decrypt → store → post-process.
 * Handles both direct kind-30078 events and NIP-59 gift-wrapped events.
 *
 * Gate order: signature → tags (the signed `g` tag is the group; a caller's scope only refuses) →
 * session scope → duplicate check (before any rate limit is charged) →
 * timestamp (lenient in [IngestionContext.RECONCILIATION]) → rate limits ([IngestionContext.LIVE]
 * only) → group + membership → decrypt → content safety → business rules → payload → insert →
 * post-process. Rows whose side effects could not run yet (missing dependency or a transient failure)
 * stay [EventEntity.APPLY_STATE_PENDING] and are re-driven by [retryDeferred]; rows whose effect can
 * never apply on this device are marked [EventEntity.APPLY_STATE_FAILED]. A correction or delete whose
 * original has not arrived is such a pending row too: it has passed every check that does not need the
 * original and is invisible to every ledger read until the original lands. Deletes apply atomically
 * with that original's insert so it never becomes visible; corrections apply on the next [retryDeferred].
 * Relays return gift wraps in outer-timestamp order and a nearby transfer can lose one frame, so a
 * correction routinely precedes its original.
 */
@Singleton
class EventProcessor
@Inject
constructor(
    private val eventDao: EventDao,
    private val groupRepo: GroupRepositoryContract,
    private val encryption: GroupEncryption,
    private val signer: EventSigner,
    private val identity: IdentityContract,
    private val giftWrap: GiftWrapService,
    private val eventValidator: EventValidator,
    private val postProcessor: EventPostProcessor,
    private val membershipHistory: MembershipHistory
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val retryLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    /**
     * Result of [process].
     *
     * @property stored true when a persisted row is applied or pending, including a retried row.
     *   False for duplicates and rejections; a rejected effect may still leave a failed row for deduplication.
     * @property outcome what happened to the record; the vocabulary shared with the nearby wire protocol
     * @property reason short diagnostic for a non-applied outcome, never shown to users
     * @property eventId id of the inner (unwrapped) event, once it is known
     * @property decrypted plaintext for notifications; omitted for already-deleted expense history
     * @property retryable true for a [IngestOutcome.REJECTED] whose cause is a record this device lacks
     *   (the key epoch it was sealed under, or an author's/participant's join): the same event may be accepted once
     *   that record lands, so a caller holding it should offer it again rather than discard it
     */
    data class ProcessResult(
        val stored: Boolean,
        val groupName: String? = null,
        val eventType: String? = null,
        val decrypted: String? = null,
        val authorHex: String? = null,
        val outcome: IngestOutcome = if (stored) IngestOutcome.APPLIED else IngestOutcome.REJECTED,
        val reason: String? = null,
        val eventId: String? = null,
        val retryable: Boolean = false
    )

    /**
     * Process a raw Nostr event through the full pipeline.
     *
     * @param rawEvent the event to process (may be gift-wrapped)
     * @param knownGroupId the group the caller is syncing; a scope constraint, exactly as
     *   [expectedGroupId]. The group an event belongs to is always its own signed `g` tag: a relay pull
     *   for one group also returns this member's envelopes for every other group, and a valid rotation
     *   for one of those must not be able to advance the pulled group's epoch.
     * @param knownGroupKey ignored; decryption only ever uses stored, epoch-bound keys, never a caller-supplied one
     * @param nonCancellable if true, post-processing runs inside [NonCancellable]
     * @param lenientTimestamp if true, allows events older than 30 days (for initial/full sync)
     * @param context why the event is being ingested; [IngestionContext.RECONCILIATION] implies
     *   [lenientTimestamp], skips the in-memory rate counters and admits pre-removal history
     * @param expectedGroupId when set, takes precedence over [knownGroupId]. An unwrapped event for
     *   another group is rejected before event or group DB access; unwrapping may read identity storage.
     * @return [ProcessResult] with the [IngestOutcome] and, when stored, the event's metadata
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun process(
        rawEvent: NostrEvent,
        knownGroupId: String? = null,
        knownGroupKey: String? = null,
        nonCancellable: Boolean = false,
        lenientTimestamp: Boolean = false,
        context: IngestionContext = IngestionContext.LIVE,
        expectedGroupId: String? = null
    ): ProcessResult {
        if (rawEvent.tags.any { it.firstOrNull() == "t" && it.getOrNull(1) == IdentityHistoryPage.TYPE } &&
            (
                rawEvent.content.length > 65536 ||
                    rawEvent.tags.size > 16 ||
                    rawEvent.tags.any { tag ->
                        tag.size > 4 || tag.any { it.length > 1024 }
                    }
                )
        ) {
            return rejected("oversized history evidence")
        }
        // 1. Unwrap gift wrap if applicable
        val unwrapResult = giftWrap.tryUnwrap(rawEvent)
        val inner = unwrapResult?.rumor ?: rawEvent
        if (unwrapResult == null && !signer.verify(inner)) return rejected("invalid signature")

        // 2. Extract tags first so the duplicate check below knows the id and group. The signed `g`
        //    tag is the only source of the group: the caller's scope can refuse an event, never rebind it.
        val tags = extractTags(inner)
        val eventType = tags.eventType
        val expenseUuid = tags.expenseUuid
        val authorHex = inner.pubkey
        val groupId = tags.groupId ?: return rejected("missing group tag", inner.id, eventType, authorHex)
        val scope = expectedGroupId ?: knownGroupId
        if (scope != null && groupId != scope) {
            Log.w(TAG, "Rejecting $eventType ${inner.id.take(8)} for group $groupId delivered in a session for $scope")
            return rejected("out of scope", inner.id, eventType, authorHex)
        }

        // 3. Duplicate check, before rate limits: a replay must not eat the author's budget, and a
        //    pending row only needs its side effects re-driven, not the whole pipeline.
        val existing = eventDao.getEvent(inner.id)
        if (existing != null) {
            if (existing.applyState == EventEntity.APPLY_STATE_APPLIED) {
                return ProcessResult(
                    stored = false,
                    eventType = eventType,
                    authorHex = authorHex,
                    outcome = IngestOutcome.ALREADY_APPLIED,
                    eventId = inner.id
                )
            }
            if (existing.applyState == EventEntity.APPLY_STATE_FAILED) {
                return rejected("effect rejected", existing.eventId, existing.eventType, existing.pubkey)
            }
            Log.i(TAG, "Re-driving pending $eventType ${inner.id.take(8)} in group $groupId")
            return applyStoredEffects(
                eventId = existing.eventId,
                eventType = existing.eventType,
                authorHex = existing.pubkey,
                content = existing.contentEncrypted,
                createdAt = existing.createdAt,
                groupId = existing.groupId,
                expenseUuid = existing.expenseUuid,
                nonCancellable = nonCancellable
            )
        }

        // 4. Validate timestamp. Reconciliation is an explicit catch-up, so age is not a signal
        //    there; malformed (<= 0) and far-future timestamps are still rejected.
        val lenient = lenientTimestamp || context == IngestionContext.RECONCILIATION
        val timestampValid =
            if (lenient) {
                eventValidator.isTimestampValidLenient(inner.createdAt)
            } else {
                eventValidator.isTimestampValid(inner.createdAt)
            }
        if (!timestampValid) {
            Log.w(TAG, "Rejecting event with invalid timestamp: ${inner.id}")
            return rejected("invalid timestamp", inner.id, eventType, authorHex)
        }

        // 5. LIVE rate limits run after the duplicate lookup but before group reads and decryption.
        //    Reconciliation is bounded by its caller instead.
        if (context == IngestionContext.LIVE) {
            if (!eventValidator.isWithinRateLimit(authorHex)) {
                Log.w(TAG, "Rate-limiting events from $authorHex")
                return rejected("author rate limit", inner.id, eventType, authorHex)
            }
            if (!eventValidator.isWithinGroupRateLimit(groupId)) {
                Log.w(TAG, "Rate-limiting events for group $groupId")
                return rejected("group rate limit", inner.id, eventType, authorHex)
            }
        }

        // 6. Validate group membership
        val group = groupRepo.getById(groupId) ?: run {
            Log.w(TAG, "Rejecting event for unknown group $groupId")
            return rejected("unknown group", inner.id, eventType, authorHex)
        }

        // Historical membership is only consulted in RECONCILIATION and at most once per event.
        var historicalAuthors: Set<String>? = null
        suspend fun historical(): Set<String> =
            historicalAuthors ?: membershipHistory.historicalAuthors(groupId).also { historicalAuthors = it }

        val membershipResult =
            validateMembership(eventType, authorHex, group, groupId, inner, context) { historical() }
        if (!membershipResult.allowed) {
            return rejected(
                if (membershipResult.exactHistory) "unlisted identity history" else "not a member",
                inner.id,
                eventType,
                authorHex
            )
        }
        val awaitsHistory = membershipResult.awaitsHistory

        // 7. Decrypt: use cached self-join decryption if available, else try epoch keys
        val decryptedContent: Decrypted =
            membershipResult.cachedDecrypted?.let { Decrypted(it, membershipResult.cachedEpoch ?: group.keyEpoch) }
                ?: decryptContent(inner.content, eventType, authorHex, group, groupId)
                ?: run {
                    // There is no legitimate reason to store ciphertext we cannot read: the epoch
                    // fallback loop already tried every known group key.
                    Log.w(TAG, "Rejecting undecryptable $eventType from $authorHex in group $groupId")
                    return rejected("undecryptable", inner.id, eventType, authorHex)
                }
        val decrypted = decryptedContent.content
        val decryptedEpoch = decryptedContent.epoch

        // A removed member's record is only history if it was sealed under a key that predates
        // their removal. See MembershipHistory for the (bounded) guarantee this gives.
        if (membershipResult.historical) {
            val removalEpoch = membershipHistory.removalEpochOf(groupId, authorHex)
            if (removalEpoch == null || decryptedEpoch >= removalEpoch) {
                Log.w(
                    TAG,
                    "Rejecting $eventType from removed member ${authorHex.take(8)}: epoch $decryptedEpoch " +
                        "is not before removal epoch $removalEpoch"
                )
                return rejected("post-removal history", inner.id, eventType, authorHex)
            }
        }

        if (eventType == IdentityHistoryPage.TYPE) {
            val evidence = IdentityHistory(groupRepo, encryption)
            val page = evidence.authenticate(inner, groupId, decryptedEpoch)
                ?: return rejected("invalid history evidence", inner.id, eventType, authorHex)
            val revocation = checkNotNull(page.revocation)
            if (groupRepo.authenticatedRevocations(groupId).none { it.id == revocation.id }) {
                val retirement = process(
                    revocation,
                    nonCancellable = nonCancellable,
                    context = IngestionContext.RECONCILIATION,
                    expectedGroupId = groupId
                )
                if (retirement.outcome !in setOf(IngestOutcome.APPLIED, IngestOutcome.ALREADY_APPLIED) ||
                    evidence.authenticate(inner, groupId, decryptedEpoch) == null
                ) {
                    return rejected("missing identity revocation", inner.id, eventType, authorHex)
                }
            }
        }

        if (!eventValidator.isContentSafe(decrypted)) {
            Log.w(TAG, "Rejecting event with unsafe content: ${inner.id}")
            return rejected("unsafe content", inner.id, eventType, authorHex)
        }

        // 8. Business rule validations. A correction or delete may legitimately outrun its original;
        //    it is then stored pending rather than refused (see the class comment).
        val rules = validateBusinessRules(
            eventType,
            authorHex,
            expenseUuid,
            groupId,
            membershipResult.exactHistory || awaitsHistory
        )
        if (rules == BusinessRules.REJECTED) return rejected("business rule", inner.id, eventType, authorHex)
        val awaitsOriginal = rules == BusinessRules.MISSING_ORIGINAL

        // 8b. Settlement counterparties use the same history in live and catch-up ingestion; an
        // offline peer must not reject a repayment an online peer accepted. This expands payload
        // participants only: the author's membership and removal-epoch checks above still apply.
        val participants =
            if (membershipResult.exactHistory || awaitsHistory) {
                group.members.toSet() + historical() + membershipHistory.formerMembers(groupId)
            } else if (context == IngestionContext.RECONCILIATION) {
                group.members.toSet() + historical()
            } else {
                group.members.toSet()
            }
        var payloadVerdict = validatePayload(eventType, decrypted, authorHex, expenseUuid, participants, inner.id)
        if (payloadVerdict == PayloadValidation.MISSING_PARTICIPANT &&
            eventType == "settlement"
        ) {
            val withFormer = participants + membershipHistory.formerMembers(groupId)
            payloadVerdict = validatePayload(eventType, decrypted, authorHex, expenseUuid, withFormer, inner.id)
        }
        when (payloadVerdict) {
            PayloadValidation.INVALID -> return rejected("invalid payload", inner.id, eventType, authorHex)
            PayloadValidation.MISSING_PARTICIPANT ->
                return rejected("missing participant", inner.id, eventType, authorHex)
            PayloadValidation.VALID -> Unit
        }
        if ((awaitsOriginal || awaitsHistory) &&
            eventDao.countPendingByAuthor(groupId, authorHex) >= MAX_PENDING_LEDGER_PER_AUTHOR
        ) {
            // Bounded retention: a member cannot fill the database with corrections to nothing. The
            // record is not stored, so a later pull or session offers it again.
            Log.w(TAG, "Rejecting $eventType from ${authorHex.take(8)}: too many records awaiting originals")
            return rejected("pending quota", inner.id, eventType, authorHex)
        }

        // 9. Store
        // A gift-wrapped rumor is unsigned; the seal signature is the proof that `authorHex` wrote
        // it. Record it under a `seal:` marker so the row is distinguishable from an unverified one
        // (backup restore trusts it; BLE/self-heal forwarding, which peers must verify, skips it).
        //
        // Types with side effects are inserted PENDING and flipped to APPLIED once the effect has
        // landed, so a crash in between leaves a row that retryDeferred can pick up instead of one
        // that looks applied but never was.
        val storedSig = if (unwrapResult != null) EventSnapshot.SEAL_SIG_PREFIX + unwrapResult.sealSig else inner.sig
        val hasSideEffects = eventType in SIDE_EFFECT_TYPES
        val pendingOnInsert = hasSideEffects || awaitsOriginal
        val entity = EventEntity(
            eventId = inner.id, groupId = groupId, pubkey = authorHex,
            createdAt = inner.createdAt, kind = NostrKind.APP_SPECIFIC, contentEncrypted = inner.content,
            eventType = eventType, expenseUuid = expenseUuid, sig = storedSig,
            receivedAt = System.currentTimeMillis() / 1000, originalEventJson = inner.toJson(),
            keyEpoch = decryptedEpoch,
            applyState = when {
                awaitsHistory -> EventEntity.APPLY_STATE_AWAITING_HISTORY
                pendingOnInsert -> EventEntity.APPLY_STATE_PENDING
                else -> EventEntity.APPLY_STATE_APPLIED
            }
        )
        val rowId = if (membershipResult.exactHistory) eventDao.insertHistory(entity) else eventDao.insert(entity)
        if (rowId == EventDao.REJECTED_DELETED) {
            // The author's delete was applied between the business-rule check and the write.
            Log.w(TAG, "Rejecting replayed deleted expense: $expenseUuid")
            return rejected("business rule", inner.id, eventType, authorHex)
        }
        if (rowId == -1L) {
            // Lost a race with a concurrent insert of the same id.
            return ProcessResult(
                stored = false,
                eventType = eventType,
                authorHex = authorHex,
                outcome = IngestOutcome.ALREADY_APPLIED,
                reason = "duplicate",
                eventId = inner.id
            )
        }

        if (awaitsHistory) {
            return ProcessResult(
                true,
                group.name,
                eventType,
                null,
                authorHex,
                outcome = IngestOutcome.DEFERRED,
                reason = "missing identity history",
                eventId = inner.id
            )
        }
        if (awaitsOriginal) {
            val held = ProcessResult(true, group.name, eventType, decrypted, authorHex, eventId = inner.id)
            // The original may have committed (import, local publish) since the business-rule check;
            // the insert then stored the delete applied and there is nothing left to hold.
            if (eventDao.getEvent(inner.id)?.applyState == EventEntity.APPLY_STATE_APPLIED) return held
            Log.i(TAG, "Holding $eventType from ${authorHex.take(8)} in group ${group.name} until its original arrives")
            return held.copy(outcome = IngestOutcome.DEFERRED, reason = "missing original")
        }

        // 10. Post-process (delegated)
        val effect =
            postProcessor.handle(
                eventType,
                decrypted,
                authorHex,
                groupId,
                inner.createdAt,
                nonCancellable,
                inner.id,
                decryptedEpoch
            )
        // The original can be admitted as deleted history. Sync callers use this plaintext to announce
        // a new expense, so omit it when a held delete took effect with the insert (or raced it).
        val notificationContent = if (eventType == "expense" &&
            expenseUuid in eventDao.getAppliedDeletedExpenseUuidsByAuthor(groupId, authorHex)
        ) {
            null
        } else {
            decrypted
        }
        val stored = ProcessResult(true, group.name, eventType, notificationContent, authorHex, eventId = inner.id)
        return when (effect) {
            PostProcessOutcome.APPLIED -> {
                if (hasSideEffects) markApplied(inner.id, nonCancellable)
                Log.i(TAG, "Stored $eventType from ${authorHex.take(8)} in group ${group.name} (${inner.id.take(8)})")
                stored
            }

            PostProcessOutcome.DEFERRED -> {
                if (!hasSideEffects) eventDao.setApplyState(inner.id, EventEntity.APPLY_STATE_PENDING)
                Log.i(TAG, "Deferred $eventType from ${authorHex.take(8)} in group ${group.name} (${inner.id.take(8)})")
                stored.copy(outcome = IngestOutcome.DEFERRED, reason = "missing dependency")
            }

            PostProcessOutcome.FAILED -> {
                if (hasSideEffects) {
                    // A control effect that did not land is not applied. The row stays pending so
                    // retryDeferred re-drives it; the receipt says so rather than claiming success.
                    Log.w(TAG, "$eventType ${inner.id.take(8)} in $groupId did not apply, kept pending")
                    stored.copy(outcome = IngestOutcome.DEFERRED, reason = "side effect failed")
                } else {
                    // Nothing about the ledger depends on this side effect; the row itself is fine.
                    stored
                }
            }

            PostProcessOutcome.REJECTED -> {
                markFailed(inner.id, nonCancellable)
                Log.w(TAG, "$eventType ${inner.id.take(8)} in $groupId can never apply here, marked failed")
                rejected("effect rejected", inner.id, eventType, authorHex)
            }
        }
    }

    /**
     * Re-run side effects for rows with `applyState = PENDING` in [groupId].
     *
     * Rows are visited oldest first, and the pass repeats while it makes progress (bounded by
     * [MAX_RETRY_PASSES]) so a rotation for epoch 1 landing in one pass unlocks epoch 2 in the next.
     * Safe to call repeatedly: a row that still cannot apply is left pending.
     *
     * @return number of rows that moved to APPLIED during this call
     */
    suspend fun retryDeferred(groupId: String): Int =
        retryLocks.getOrPut(groupId) { Mutex() }.withLock { retryDeferredLocked(groupId) }

    /** Startup recovery does not require opening the Nearby screen or receiving a duplicate. */
    suspend fun recoverPending() {
        for (group in groupRepo.getAll()) {
            try {
                retryDeferred(group.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Pending recovery failed for ${group.id.take(8)}: ${e.message}")
            }
        }
    }

    private suspend fun retryDeferredLocked(groupId: String): Int {
        var applied = 0
        repeat(MAX_RETRY_PASSES) {
            val pending = eventDao.getPendingEvents(groupId)
            if (pending.isEmpty()) return applied
            var progress = 0
            for (row in pending) {
                if (eventDao.getEvent(row.eventId)?.applyState !in setOf(
                        EventEntity.APPLY_STATE_PENDING,
                        EventEntity.APPLY_STATE_AWAITING_HISTORY
                    )
                ) {
                    continue
                }
                val result = applyStoredEffects(
                    eventId = row.eventId,
                    eventType = row.eventType,
                    authorHex = row.pubkey,
                    content = row.contentEncrypted,
                    createdAt = row.createdAt,
                    groupId = row.groupId,
                    expenseUuid = row.expenseUuid,
                    nonCancellable = false
                )
                if (result.outcome == IngestOutcome.APPLIED) progress++
            }
            applied += progress
            if (progress == 0) return applied
        }
        return applied
    }

    /**
     * Decrypt an already-stored row and run its side effects, flipping it to APPLIED on success. The
     * signatures were validated at insertion, but membership-changing effects recheck their authority
     * against the live epoch in EventPostProcessor and the repository's atomic mutation guard.
     * On [PostProcessOutcome.FAILED] the row stays pending (retried later); on
     * [PostProcessOutcome.REJECTED] it is marked failed and no longer retried.
     *
     * A pending correction or delete has no side effect to run; it is applied as soon as an applied
     * original by the same author exists for its expense, and stays pending until then.
     */
    private suspend fun applyStoredEffects(
        eventId: String,
        eventType: String,
        authorHex: String,
        content: String,
        createdAt: Long,
        groupId: String,
        expenseUuid: String?,
        nonCancellable: Boolean
    ): ProcessResult {
        val group = groupRepo.getById(groupId)
            ?: return rejected("unknown group", eventId, eventType, authorHex)
        if (eventType in IdentityHistoryPage.MONEY_TYPES &&
            authorHex in groupRepo.retiredIdentities(groupId).revoked
        ) {
            val evidence = membershipHistory.identityEvidence(groupId, encryption)
            if (!evidence.permits(authorHex, eventId)) {
                if (authorHex in evidence.authorized) {
                    markFailed(eventId, nonCancellable)
                    return rejected("unlisted identity history", eventId, eventType, authorHex)
                }
                return ProcessResult(
                    true,
                    group.name,
                    eventType,
                    null,
                    authorHex,
                    outcome = IngestOutcome.DEFERRED,
                    reason = "missing identity history",
                    eventId = eventId
                )
            }
        }
        if (eventDao.getEvent(eventId)?.applyState == EventEntity.APPLY_STATE_AWAITING_HISTORY) {
            val plaintext = decryptContent(content, eventType, authorHex, group, groupId)
                ?: return rejected("undecryptable", eventId, eventType, authorHex)
            val participants = group.members.toSet() + membershipHistory.historicalAuthors(groupId) +
                membershipHistory.formerMembers(groupId)
            if (validatePayload(eventType, plaintext.content, authorHex, expenseUuid, participants, eventId) !=
                PayloadValidation.VALID
            ) {
                return rejected("missing participant", eventId, eventType, authorHex)
            }
            if (eventType !in ORIGINAL_DEPENDENT_TYPES) {
                markApplied(eventId, nonCancellable)
                return ProcessResult(true, group.name, eventType, null, authorHex, eventId = eventId)
            }
        }
        if (eventType in ORIGINAL_DEPENDENT_TYPES) {
            val original = expenseUuid?.let { eventDao.getExpenseByAuthor(it, groupId, authorHex) }
            val base = ProcessResult(true, group.name, eventType, null, authorHex, eventId = eventId)
            if (original == null) return base.copy(outcome = IngestOutcome.DEFERRED, reason = "missing original")
            markApplied(eventId, nonCancellable)
            Log.i(TAG, "Applied held $eventType ${eventId.take(8)} in group ${group.name}: original arrived")
            return base
        }
        val decrypted = decryptContent(content, eventType, authorHex, group, groupId)
            ?: return rejected("undecryptable", eventId, eventType, authorHex)
        val effect =
            postProcessor.handle(
                eventType,
                decrypted.content,
                authorHex,
                groupId,
                createdAt,
                nonCancellable,
                eventId,
                decrypted.epoch
            )
        val base = ProcessResult(true, group.name, eventType, decrypted.content, authorHex, eventId = eventId)
        return when (effect) {
            PostProcessOutcome.APPLIED -> {
                markApplied(eventId, nonCancellable)
                Log.i(TAG, "Applied pending $eventType ${eventId.take(8)} in group ${group.name}")
                base
            }

            PostProcessOutcome.DEFERRED -> base.copy(outcome = IngestOutcome.DEFERRED, reason = "missing dependency")
            PostProcessOutcome.FAILED -> base.copy(outcome = IngestOutcome.DEFERRED, reason = "side effect failed")
            PostProcessOutcome.REJECTED -> {
                markFailed(eventId, nonCancellable)
                base.copy(stored = false, outcome = IngestOutcome.REJECTED, reason = "effect rejected")
            }
        }
    }

    /** Flip a row to APPLIED; not interruptible when the effect it records ran non-cancellably. */
    private suspend fun markApplied(eventId: String, nonCancellable: Boolean) = setState(
        eventId,
        EventEntity.APPLY_STATE_APPLIED,
        nonCancellable
    )

    /** Park a row whose effect can never apply here; it is kept for dedup and not retried. */
    private suspend fun markFailed(eventId: String, nonCancellable: Boolean) = setState(
        eventId,
        EventEntity.APPLY_STATE_FAILED,
        nonCancellable
    )

    private suspend fun setState(eventId: String, state: Int, nonCancellable: Boolean) {
        if (nonCancellable) {
            withContext(NonCancellable) { eventDao.setApplyState(eventId, state) }
        } else {
            eventDao.setApplyState(eventId, state)
        }
    }

    private data class Tags(val groupId: String?, val eventType: String, val expenseUuid: String?)

    /** Uses the first `g` and last `t`/`x` values; the caller cannot override the signed group tag. */
    private fun extractTags(inner: NostrEvent): Tags {
        var groupId: String? = null
        var eventType = "unknown"
        var expenseUuid: String? = null
        for (tag in inner.tags) {
            if (tag.size >= 2) {
                when (tag[0]) {
                    "g" -> if (groupId == null) groupId = tag[1]
                    "t" -> eventType = tag[1]
                    "x" -> expenseUuid = tag[1]
                }
            }
        }
        return Tags(groupId, eventType, expenseUuid)
    }

    /** Plaintext plus the key epoch that produced it. */
    private data class Decrypted(val content: String, val epoch: Int)

    /**
     * Try the current group key, then every older epoch key, then (for `key_rotation`) the NIP-44
     * conversation key between this device and the author. Null when nothing opens the content.
     */
    private suspend fun decryptContent(
        content: String,
        eventType: String,
        authorHex: String,
        group: Group,
        groupId: String
    ): Decrypted? {
        // Never label an arbitrary cached key with the current epoch. Each candidate is loaded by
        // its immutable epoch, including after a rotation in the middle of a relay pull.
        for (epoch in group.keyEpoch downTo 0) {
            val key = groupRepo.getGroupKeyForEpoch(groupId, epoch) ?: continue
            tryDecrypt(content, key)?.let { return Decrypted(it, epoch) }
        }
        // key_rotation events are encrypted per-member with NIP-44 conversation keys
        if (eventType == TYPE_KEY_ROTATION) {
            val plaintext = try {
                val privKey = identity.getPrivateKeyBytes()
                try {
                    val convKey = Nip44.getConversationKey(privKey, authorHex.hexToBytes())
                    Nip44.decrypt(content, convKey)
                } finally {
                    privKey.fill(0)
                }
            } catch (_: Exception) {
                null
            }
            if (plaintext != null) return Decrypted(plaintext, group.keyEpoch)
        }
        return null
    }

    private fun tryDecrypt(content: String, key: String): String? = try {
        encryption.decrypt(content, key)
    } catch (_: Exception) {
        null
    }

    /**
     * Result of membership validation, optionally carrying a cached decryption.
     *
     * @property historical the author is not a current member but appears in the group's membership
     *   history; the final decision is made after decryption against the removal epoch
     */
    private data class MembershipResult(
        val allowed: Boolean,
        val cachedDecrypted: String? = null,
        val historical: Boolean = false,
        val cachedEpoch: Int? = null,
        val exactHistory: Boolean = false,
        val awaitsHistory: Boolean = false
    )

    private suspend fun validateMembership(
        eventType: String,
        authorHex: String,
        group: Group,
        groupId: String,
        inner: NostrEvent,
        context: IngestionContext,
        historicalAuthors: suspend () -> Set<String>
    ): MembershipResult {
        if (eventType == IdentityHistoryPage.TYPE) return MembershipResult(true)
        if (eventType in IdentityHistoryPage.MONEY_TYPES &&
            authorHex in groupRepo.retiredIdentities(groupId).revoked
        ) {
            val evidence = membershipHistory.identityEvidence(groupId, encryption)
            return if (evidence.permits(authorHex, inner.id)) {
                MembershipResult(true, exactHistory = true)
            } else if (authorHex in evidence.authorized) {
                MembershipResult(false, exactHistory = true)
            } else {
                MembershipResult(true, awaitsHistory = true)
            }
        }
        if (authorHex !in group.members) {
            if (eventType == TYPE_KEY_REVOCATION) {
                // Admit either an old-key revocation completing a current-epoch replacement, or a
                // competing revocation with successor proof and a prior retirement in its retained epoch.
                // This admits control evidence, not a self-join; post-processing still checks authority.
                for (epoch in group.keyEpoch downTo 0) {
                    val key = groupRepo.getGroupKeyForEpoch(groupId, epoch) ?: continue
                    val plaintext = tryDecrypt(inner.content, key) ?: continue
                    val revocation = try {
                        json.decodeFromString<KeyRevocation>(plaintext)
                    } catch (
                        _: Exception
                    ) {
                        continue
                    }
                    val completesTransition = epoch == group.keyEpoch && revocation.newPubkey in group.members
                    val competes =
                        groupRepo.hasRevocationInEpoch(groupId, authorHex, epoch) && revocation.provesSuccessor(groupId)
                    if (revocation.isAuthorizedBy(authorHex) && (completesTransition || competes)) {
                        return MembershipResult(true, plaintext, cachedEpoch = epoch)
                    }
                }
                Log.w(TAG, "Rejecting key_revocation from non-member $authorHex in group $groupId")
                return MembershipResult(false)
            }
            if (eventType != "group_meta") {
                // Reconciliation may carry the ledger history of someone since removed. Admit it
                // provisionally; process() rejects it after decryption unless the record was sealed
                // under a key epoch older than their removal. LIVE traffic never takes this path.
                if (context == IngestionContext.RECONCILIATION &&
                    eventType in HISTORY_TYPES &&
                    authorHex in historicalAuthors()
                ) {
                    Log.i(TAG, "Admitting $eventType from former member ${authorHex.take(8)} for reconciliation")
                    return MembershipResult(allowed = true, historical = true)
                }
                Log.w(TAG, "Rejecting $eventType from non-member $authorHex in group $groupId")
                return MembershipResult(false)
            }
        }
        if (eventType == "group_meta") {
            val isCreator = group.createdBy.isNotEmpty() && authorHex == group.createdBy
            if (!isCreator && authorHex !in group.members) {
                val historical = decryptContent(inner.content, eventType, authorHex, group, groupId)
                val meta = historical?.let {
                    try {
                        json.decodeFromString<GroupMeta>(it.content)
                    } catch (_: kotlinx.serialization.SerializationException) {
                        null
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                }
                if (historical != null && meta != null && meta.creatorTransitions.isNotEmpty()) {
                    val preview = groupRepo.previewCreatorBootstrap(
                        groupId,
                        meta.originalCreator,
                        meta.createdAt,
                        meta.creatorTransitions
                    )
                    val carriesProof = meta.creatorTransitions.any {
                        it.oldPubkey == authorHex && it.newPubkey == meta.createdBy
                    }
                    if (preview != null && (preview.createdBy == authorHex || carriesProof)) {
                        return MembershipResult(true, historical.content, cachedEpoch = historical.epoch)
                    }
                }
                if (historical != null &&
                    meta != null &&
                    groupRepo.isHistoricalCreatorMeta(group, meta, authorHex, inner.createdAt, inner.id)
                ) {
                    return MembershipResult(true, historical.content, cachedEpoch = historical.epoch)
                }
                // A tombstoned key cannot come back as a member, however it was sealed.
                if (groupRepo.resolveRoster(groupId, listOf(authorHex)) != listOf(authorHex)) {
                    Log.w(TAG, "Rejecting group_meta from revoked identity ${authorHex.take(8)} in group $groupId")
                    return MembershipResult(false)
                }
                val key = groupRepo.getGroupKeyForEpoch(groupId, group.keyEpoch) ?: return MembershipResult(false)
                val decryptedContent = historical?.takeIf { it.epoch == group.keyEpoch }?.content
                    ?: tryDecrypt(inner.content, key)
                // A self-join must use the current key and add nobody but its author. Omitted members
                // and stale group fields are allowed: post-processing applies only the author's join/name,
                // so concurrent joiners need not know about each other.
                val isSelfJoin = try {
                    if (decryptedContent == null) {
                        false
                    } else {
                        val meta = json.decodeFromString<GroupMeta>(decryptedContent)
                        val proposed = meta.members.toSet()
                        authorHex in proposed && (proposed - group.members.toSet() - authorHex).isEmpty()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Self-join check failed for $authorHex in $groupId: ${e.message}")
                    false
                }
                if (!isSelfJoin) {
                    Log.w(TAG, "Rejecting group_meta from non-creator $authorHex in group $groupId")
                    return MembershipResult(false)
                }
                // Return cached decryption to avoid re-decrypting later
                return MembershipResult(true, decryptedContent)
            }
        }
        if (eventType == TYPE_KEY_ROTATION && group.createdBy.isNotEmpty() && authorHex != group.createdBy) {
            Log.w(TAG, "Rejecting key_rotation from non-creator $authorHex in group $groupId")
            return MembershipResult(false)
        }
        return MembershipResult(true)
    }

    /** Outcome of [validateBusinessRules]. */
    private enum class BusinessRules { OK, REJECTED, MISSING_ORIGINAL }

    /**
     * Expense identity is bound to its author: a correction or delete only resolves against an
     * expense the same pubkey stored, and a replayed expense is only a tombstoned replay if the same
     * author deleted that uuid. Two members can therefore never collide on (or hijack) each other's ids.
     *
     * Because the lookup is author-qualified, an original that exists is always by the right author; the
     * only open question for a correction or delete is whether that original has arrived yet.
     */
    private suspend fun validateBusinessRules(
        eventType: String,
        authorHex: String,
        expenseUuid: String?,
        groupId: String,
        preserveHistory: Boolean = false
    ): BusinessRules {
        if (eventType in ORIGINAL_DEPENDENT_TYPES) {
            if (expenseUuid == null) {
                Log.w(TAG, "Rejecting $eventType without an expense id")
                return BusinessRules.REJECTED
            }
            val originalCreator = eventDao.getExpenseByAuthor(expenseUuid, groupId, authorHex)?.pubkey
            if (originalCreator == null) return BusinessRules.MISSING_ORIGINAL
            if (!eventValidator.isCorrectionAuthorValid(eventType, authorHex, originalCreator)) {
                Log.w(TAG, "Rejecting $eventType: author $authorHex is not the original creator")
                return BusinessRules.REJECTED
            }
        }
        if (eventType == "expense" && expenseUuid != null && !preserveHistory) {
            // Pending deletes need this history to satisfy their dependency, and failed deletes never
            // took effect. EventDao.insert repeats this check inside the write transaction.
            val deletedUuids = eventDao.getAppliedDeletedExpenseUuidsByAuthor(groupId, authorHex).toSet()
            if (eventValidator.isDeletedExpense(eventType, expenseUuid, deletedUuids)) {
                Log.w(TAG, "Rejecting replayed deleted expense: $expenseUuid")
                return BusinessRules.REJECTED
            }
        }
        // Intentionally no timestamp-ordering rule between expenses and settlements.
        // Balances are order-independent sums over all stored events, so accepting an
        // expense whose created_at precedes a settlement cannot corrupt the ledger.
        // Ordinary clock skew between phones (plus the +1h future tolerance) would
        // otherwise silently and permanently drop legitimate expenses on every peer
        // except the author.
        return BusinessRules.OK
    }

    private enum class PayloadValidation { VALID, INVALID, MISSING_PARTICIPANT }

    /**
     * Parse and validate the decrypted payload of expense/settlement events.
     * The `x` tag ([expenseUuid]) is required and must match the id embedded in the payload so a
     * sender cannot attach one UUID in the envelope and a different one inside; balance computation
     * reads a covered settlement's dedup identity from that tag without decrypting it.
     */
    private fun validatePayload(
        eventType: String,
        decrypted: String,
        authorHex: String,
        expenseUuid: String?,
        members: Set<String>,
        eventId: String
    ): PayloadValidation {
        when (eventType) {
            "expense", "expense_correction" -> {
                val expense =
                    try {
                        json.decodeFromString<Expense>(decrypted)
                    } catch (_: Exception) {
                        Log.w(TAG, "Rejecting $eventType with unparseable content: $eventId")
                        return PayloadValidation.INVALID
                    }
                if (expense.id != expenseUuid) {
                    Log.w(TAG, "Rejecting $eventType: x tag $expenseUuid does not match payload id ${expense.id}")
                    return PayloadValidation.INVALID
                }
                if (!eventValidator.isExpenseValid(expense, members)) {
                    val parties = expense.splitAmong.mapTo(HashSet()) { it.pubkey }.also { it.add(expense.paidBy) }
                    // Expanded parties diagnose missing membership only; they never authorize storage.
                    if (!members.containsAll(parties) && eventValidator.isExpenseValid(expense, members + parties)) {
                        return PayloadValidation.MISSING_PARTICIPANT
                    }
                    Log.w(TAG, "Rejecting $eventType with invalid payload: $eventId")
                    return PayloadValidation.INVALID
                }
            }

            "settlement" -> {
                val settlement =
                    try {
                        json.decodeFromString<Settlement>(decrypted)
                    } catch (_: Exception) {
                        Log.w(TAG, "Rejecting settlement with unparseable content: $eventId")
                        return PayloadValidation.INVALID
                    }
                if (settlement.id != expenseUuid) {
                    Log.w(TAG, "Rejecting settlement: x tag $expenseUuid does not match payload id ${settlement.id}")
                    return PayloadValidation.INVALID
                }
                if (!eventValidator.isSettlementValid(settlement, authorHex, members)) {
                    val parties = setOf(settlement.from, settlement.to)
                    if (!members.containsAll(parties) &&
                        eventValidator.isSettlementValid(settlement, authorHex, members + parties)
                    ) {
                        return PayloadValidation.MISSING_PARTICIPANT
                    }
                    Log.w(TAG, "Rejecting settlement with invalid payload: $eventId")
                    return PayloadValidation.INVALID
                }
            }
        }
        return PayloadValidation.VALID
    }

    private fun rejected(
        reason: String,
        eventId: String? = null,
        eventType: String? = null,
        authorHex: String? = null
    ) = ProcessResult(
        stored = false,
        eventType = eventType,
        authorHex = authorHex,
        outcome = IngestOutcome.REJECTED,
        reason = reason,
        eventId = eventId,
        retryable = reason in RETRYABLE_REASONS
    )

    suspend fun unresolvedIdentityHistory(groupId: String): Int {
        val rows = eventDao.getEventsByGroup(groupId)
        val evidence = membershipHistory.identityEvidence(groupId, encryption)
        val missing = evidence.authorized.any { (author, ids) ->
            val local = rows.filter { it.pubkey == author && it.eventType in IdentityHistoryPage.MONEY_TYPES }
                .mapTo(HashSet()) { it.eventId }
            !local.containsAll(ids) || !ids.containsAll(local)
        }
        return evidence.unresolved.size + if (missing) 1 else 0
    }

    companion object {
        private const val TAG = "EventProcessor"
        private const val TYPE_KEY_ROTATION = "key_rotation"
        private const val TYPE_KEY_REVOCATION = "key_revocation"

        /** Types whose post-processing mutates group state; inserted PENDING and flipped once it lands. */
        private val SIDE_EFFECT_TYPES = setOf("group_meta", TYPE_KEY_ROTATION, TYPE_KEY_REVOCATION)

        /** Ledger record types a former member may legitimately have authored before their removal. */
        private val HISTORY_TYPES = setOf("expense", "settlement", "expense_correction", "expense_delete")

        /** Types that apply only once the same author's original expense is stored. */
        private val ORIGINAL_DEPENDENT_TYPES = setOf("expense_correction", "expense_delete")

        /** Rejections a missing key epoch or author/participant membership record could lift. */
        private val RETRYABLE_REASONS = setOf("undecryptable", "not a member", "missing participant")

        /** Rows one author may hold pending for missing originals in one group. */
        internal const val MAX_PENDING_LEDGER_PER_AUTHOR = 128

        /** Upper bound on [retryDeferred] passes; each pass must apply at least one row to continue. */
        private const val MAX_RETRY_PASSES = 8
    }
}
