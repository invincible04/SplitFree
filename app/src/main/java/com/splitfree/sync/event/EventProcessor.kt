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
import com.splitfree.domain.repository.EventSnapshot
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.util.hexToBytes
import com.splitfree.domain.validation.EventValidator
import com.splitfree.util.DebugLog as Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Event processing pipeline: unwrap → validate → decrypt → store → post-process.
 * Handles both direct kind-30078 events and NIP-59 gift-wrapped events.
 *
 * Gate order: signature → tags → session scope → duplicate check (before any rate limit is charged) →
 * timestamp (lenient in [IngestionContext.RECONCILIATION]) → rate limits ([IngestionContext.LIVE]
 * only) → group + membership → decrypt → content safety → business rules → payload → insert →
 * post-process. Rows whose side effects could not run yet (missing dependency or a transient failure)
 * stay [EventEntity.APPLY_STATE_PENDING] and are re-driven by [retryDeferred]; rows whose effect can
 * never apply on this device are marked [EventEntity.APPLY_STATE_FAILED].
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

    /**
     * Result of [process].
     *
     * @property stored true when the row is durably persisted: [IngestOutcome.APPLIED] and
     *   [IngestOutcome.DEFERRED]. False for [IngestOutcome.REJECTED] and for a duplicate
     *   ([IngestOutcome.ALREADY_APPLIED]), which persisted nothing new.
     * @property outcome what happened to the record; the vocabulary shared with the nearby wire protocol
     * @property reason short diagnostic for a non-applied outcome, never shown to users
     * @property eventId id of the inner (unwrapped) event, once it is known
     */
    data class ProcessResult(
        val stored: Boolean,
        val groupName: String? = null,
        val eventType: String? = null,
        val decrypted: String? = null,
        val authorHex: String? = null,
        val outcome: IngestOutcome = if (stored) IngestOutcome.APPLIED else IngestOutcome.REJECTED,
        val reason: String? = null,
        val eventId: String? = null
    )

    /**
     * Process a raw Nostr event through the full pipeline.
     *
     * @param rawEvent the event to process (may be gift-wrapped)
     * @param knownGroupId override group ID extraction from tags
     * @param knownGroupKey override group key lookup from encrypted storage
     * @param nonCancellable if true, post-processing runs inside [NonCancellable]
     * @param lenientTimestamp if true, allows events older than 30 days (for initial/full sync)
     * @param context why the event is being ingested; [IngestionContext.RECONCILIATION] implies
     *   [lenientTimestamp], skips the in-memory rate counters and admits pre-removal history
     * @param expectedGroupId when set, the event (after unwrapping) must belong to exactly this group or
     *   it is rejected before anything is read or written. A nearby session is authenticated for one
     *   group; an envelope it delivers must not be able to mutate another.
     * @return [ProcessResult] with the [IngestOutcome] and, when stored, the event's metadata
     */
    suspend fun process(
        rawEvent: NostrEvent,
        knownGroupId: String? = null,
        knownGroupKey: String? = null,
        nonCancellable: Boolean = false,
        lenientTimestamp: Boolean = false,
        context: IngestionContext = IngestionContext.LIVE,
        expectedGroupId: String? = null
    ): ProcessResult {
        // 1. Unwrap gift wrap if applicable
        val unwrapResult = giftWrap.tryUnwrap(rawEvent)
        val inner = unwrapResult?.rumor ?: rawEvent
        if (unwrapResult == null && !signer.verify(inner)) return rejected("invalid signature")

        // 2. Extract tags first so the duplicate check below knows the id and group
        val tags = extractTags(inner, knownGroupId)
        val eventType = tags.eventType
        val expenseUuid = tags.expenseUuid
        val authorHex = inner.pubkey
        val groupId = tags.groupId ?: return rejected("missing group tag", inner.id, eventType, authorHex)
        if (expectedGroupId != null && groupId != expectedGroupId) {
            Log.w(TAG, "Rejecting $eventType ${inner.id.take(8)} for group $groupId delivered in a session for another")
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
            Log.i(TAG, "Re-driving pending $eventType ${inner.id.take(8)} in group $groupId")
            return applyStoredEffects(
                eventId = inner.id,
                eventType = eventType,
                authorHex = authorHex,
                content = inner.content,
                createdAt = inner.createdAt,
                groupId = groupId,
                knownGroupKey = knownGroupKey,
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

        // 5. Rate limits, LIVE only: unsolicited traffic is bounded here, before any DB read so a flood
        //    of junk costs only the in-memory counters. A reconciliation is bounded by its caller.
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
            validateMembership(eventType, authorHex, group, knownGroupKey, groupId, inner, context) { historical() }
        if (!membershipResult.allowed) return rejected("not a member", inner.id, eventType, authorHex)

        // 7. Decrypt: use cached self-join decryption if available, else try epoch keys
        val decryptedContent: Decrypted =
            membershipResult.cachedDecrypted?.let { Decrypted(it, group.keyEpoch) }
                ?: decryptContent(inner.content, eventType, authorHex, group, groupId, knownGroupKey)
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

        if (!eventValidator.isContentSafe(decrypted)) {
            Log.w(TAG, "Rejecting event with unsafe content: ${inner.id}")
            return rejected("unsafe content", inner.id, eventType, authorHex)
        }

        // 8. Business rule validations
        if (!validateBusinessRules(eventType, authorHex, expenseUuid, groupId)) {
            return rejected("business rule", inner.id, eventType, authorHex)
        }

        // 8b. Validate remote payloads before storing. Participants may include past members when
        //     reconciling history; live traffic is checked against the current roster only.
        val participants =
            if (context == IngestionContext.RECONCILIATION) {
                group.members.toSet() + historical()
            } else {
                group.members.toSet()
            }
        if (!validatePayload(eventType, decrypted, authorHex, expenseUuid, participants, inner.id)) {
            return rejected("invalid payload", inner.id, eventType, authorHex)
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
        val inserted = eventDao.insertIfNew(
            EventEntity(
                eventId = inner.id, groupId = groupId, pubkey = authorHex,
                createdAt = inner.createdAt, kind = NostrKind.APP_SPECIFIC, contentEncrypted = inner.content,
                eventType = eventType, expenseUuid = expenseUuid, sig = storedSig,
                receivedAt = System.currentTimeMillis() / 1000, originalEventJson = inner.toJson(),
                keyEpoch = decryptedEpoch,
                applyState = if (hasSideEffects) EventEntity.APPLY_STATE_PENDING else EventEntity.APPLY_STATE_APPLIED
            )
        )
        if (!inserted) {
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
        val stored = ProcessResult(true, group.name, eventType, decrypted, authorHex, eventId = inner.id)
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
    suspend fun retryDeferred(groupId: String): Int {
        var applied = 0
        repeat(MAX_RETRY_PASSES) {
            val pending = eventDao.getPendingEvents(groupId)
            if (pending.isEmpty()) return applied
            var progress = 0
            for (row in pending) {
                val result = applyStoredEffects(
                    eventId = row.eventId,
                    eventType = row.eventType,
                    authorHex = row.pubkey,
                    content = row.contentEncrypted,
                    createdAt = row.createdAt,
                    groupId = row.groupId,
                    knownGroupKey = null,
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
     * row was fully validated when it was inserted, so membership and payload rules are not repeated.
     * On [PostProcessOutcome.FAILED] the row stays pending (retried later); on
     * [PostProcessOutcome.REJECTED] it is marked failed and no longer retried.
     */
    private suspend fun applyStoredEffects(
        eventId: String,
        eventType: String,
        authorHex: String,
        content: String,
        createdAt: Long,
        groupId: String,
        knownGroupKey: String?,
        nonCancellable: Boolean
    ): ProcessResult {
        val group = groupRepo.getById(groupId)
            ?: return rejected("unknown group", eventId, eventType, authorHex)
        val decrypted = decryptContent(content, eventType, authorHex, group, groupId, knownGroupKey)
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

    private fun extractTags(inner: NostrEvent, knownGroupId: String?): Tags {
        var groupId = knownGroupId
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
        groupId: String,
        knownGroupKey: String?
    ): Decrypted? {
        val groupKey = knownGroupKey ?: groupRepo.getGroupKey(groupId) ?: return null
        tryDecrypt(content, groupKey)?.let { return Decrypted(it, group.keyEpoch) }
        if (group.keyEpoch > 0) {
            // Event may have been encrypted with an older epoch key (race condition, or history)
            for (epoch in (group.keyEpoch - 1) downTo 0) {
                val oldKey = groupRepo.getGroupKeyForEpoch(groupId, epoch) ?: continue
                tryDecrypt(content, oldKey)?.let { return Decrypted(it, epoch) }
            }
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
        val historical: Boolean = false
    )

    private suspend fun validateMembership(
        eventType: String,
        authorHex: String,
        group: Group,
        knownGroupKey: String?,
        groupId: String,
        inner: NostrEvent,
        context: IngestionContext,
        historicalAuthors: suspend () -> Set<String>
    ): MembershipResult {
        if (authorHex !in group.members) {
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
                val key = knownGroupKey ?: groupRepo.getGroupKey(groupId) ?: ""
                val decryptedContent = tryDecrypt(inner.content, key)
                // A self-join is a meta, sealed under the CURRENT group key (so its author holds an
                // invite), whose roster adds nobody but its author. What it says about the name,
                // relays or other members is irrelevant: applyMemberSelfUpdate only ever records the
                // author's own membership and display name. Two members joining concurrently (each
                // unaware of the other) or a joiner unaware of a recent rename are therefore admitted
                // in any arrival order; a removed member cannot pass because they lack the current key.
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

    /**
     * Expense identity is bound to its author (NS-15): a correction or delete only resolves against an
     * expense the same pubkey stored, and a replayed expense is only a tombstoned replay if the same
     * author deleted that uuid. Two members can therefore never collide on (or hijack) each other's ids.
     */
    private suspend fun validateBusinessRules(
        eventType: String,
        authorHex: String,
        expenseUuid: String?,
        groupId: String
    ): Boolean {
        if (eventType == "expense_correction" || eventType == "expense_delete") {
            val originalCreator = expenseUuid?.let { eventDao.getExpenseByAuthor(it, groupId, authorHex)?.pubkey }
            if (!eventValidator.isCorrectionAuthorValid(eventType, authorHex, originalCreator)) {
                Log.w(TAG, "Rejecting $eventType: author $authorHex is not the original creator")
                return false
            }
        }
        if (eventType == "expense" && expenseUuid != null) {
            val deletedUuids = eventDao.getDeletedExpenseUuidsByAuthor(groupId, authorHex).toSet()
            if (eventValidator.isDeletedExpense(eventType, expenseUuid, deletedUuids)) {
                Log.w(TAG, "Rejecting replayed deleted expense: $expenseUuid")
                return false
            }
        }
        // Intentionally no timestamp-ordering rule between expenses and settlements.
        // Balances are order-independent sums over all stored events, so accepting an
        // expense whose created_at precedes a settlement cannot corrupt the ledger.
        // Ordinary clock skew between phones (plus the +1h future tolerance) would
        // otherwise silently and permanently drop legitimate expenses on every peer
        // except the author.
        return true
    }

    /**
     * Parse and validate the decrypted payload of expense/settlement events.
     * The `x` tag ([expenseUuid]) must match the id embedded in the payload so a
     * sender cannot attach one UUID in the envelope and a different one inside.
     */
    private fun validatePayload(
        eventType: String,
        decrypted: String,
        authorHex: String,
        expenseUuid: String?,
        members: Set<String>,
        eventId: String
    ): Boolean {
        when (eventType) {
            "expense", "expense_correction" -> {
                val expense =
                    try {
                        json.decodeFromString<Expense>(decrypted)
                    } catch (_: Exception) {
                        Log.w(TAG, "Rejecting $eventType with unparseable content: $eventId")
                        return false
                    }
                if (!eventValidator.isExpenseValid(expense, members)) {
                    Log.w(TAG, "Rejecting $eventType with invalid payload: $eventId")
                    return false
                }
                if (expense.id != expenseUuid) {
                    Log.w(TAG, "Rejecting $eventType: x tag $expenseUuid does not match payload id ${expense.id}")
                    return false
                }
            }

            "settlement" -> {
                val settlement =
                    try {
                        json.decodeFromString<Settlement>(decrypted)
                    } catch (_: Exception) {
                        Log.w(TAG, "Rejecting settlement with unparseable content: $eventId")
                        return false
                    }
                if (!eventValidator.isSettlementValid(settlement, authorHex, members)) {
                    Log.w(TAG, "Rejecting settlement with invalid payload: $eventId")
                    return false
                }
                if (expenseUuid != null && settlement.id != expenseUuid) {
                    Log.w(TAG, "Rejecting settlement: x tag $expenseUuid does not match payload id ${settlement.id}")
                    return false
                }
            }
        }
        return true
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
        eventId = eventId
    )

    companion object {
        private const val TAG = "EventProcessor"
        private const val TYPE_KEY_ROTATION = "key_rotation"

        /** Types whose post-processing mutates group state; inserted PENDING and flipped once it lands. */
        private val SIDE_EFFECT_TYPES = setOf("group_meta", TYPE_KEY_ROTATION, "key_revocation")

        /** Ledger record types a former member may legitimately have authored before their removal. */
        private val HISTORY_TYPES = setOf("expense", "settlement", "expense_correction", "expense_delete")

        /** Upper bound on [retryDeferred] passes; each pass must apply at least one row to continue. */
        private const val MAX_RETRY_PASSES = 8
    }
}
