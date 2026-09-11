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
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.repository.EventSnapshot
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.util.hexToBytes
import com.splitfree.domain.validation.EventValidator
import com.splitfree.util.DebugLog as Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/**
 * Event processing pipeline: unwrap → validate → decrypt → store → post-process.
 * Handles both direct kind-30078 events and NIP-59 gift-wrapped events.
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
    private val postProcessor: EventPostProcessor
) {
    private val json = Json { ignoreUnknownKeys = true }

    data class ProcessResult(
        val stored: Boolean,
        val groupName: String? = null,
        val eventType: String? = null,
        val decrypted: String? = null,
        val authorHex: String? = null
    )

    /**
     * Process a raw Nostr event through the full pipeline.
     *
     * @param rawEvent the event to process (may be gift-wrapped)
     * @param knownGroupId override group ID extraction from tags
     * @param knownGroupKey override group key lookup from encrypted storage
     * @param nonCancellable if true, post-processing runs inside [NonCancellable]
     * @param lenientTimestamp if true, allows events older than 30 days (for initial/full sync)
     * @return [ProcessResult] indicating whether the event was stored and its metadata
     */
    suspend fun process(
        rawEvent: NostrEvent,
        knownGroupId: String? = null,
        knownGroupKey: String? = null,
        nonCancellable: Boolean = false,
        lenientTimestamp: Boolean = false
    ): ProcessResult {
        // 1. Unwrap gift wrap if applicable
        val unwrapResult = giftWrap.tryUnwrap(rawEvent)
        val inner = unwrapResult?.rumor ?: rawEvent
        if (unwrapResult == null && !signer.verify(inner)) return ProcessResult(false)

        // 2. Validate timestamp
        val timestampValid =
            if (lenientTimestamp) {
                eventValidator.isTimestampValidLenient(inner.createdAt)
            } else {
                eventValidator.isTimestampValid(inner.createdAt)
            }
        if (!timestampValid) {
            Log.w(TAG, "Rejecting event with invalid timestamp: ${inner.id}")
            return ProcessResult(false)
        }

        // 3. Extract tags
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
        groupId ?: return ProcessResult(false)

        // 4. Rate limits, checked before any DB read so a flood of junk costs only the in-memory
        //    counters, not a group lookup per event.
        val authorHex = inner.pubkey
        if (!eventValidator.isWithinRateLimit(authorHex)) {
            Log.w(TAG, "Rate-limiting events from $authorHex")
            return ProcessResult(false)
        }
        if (!eventValidator.isWithinGroupRateLimit(groupId)) {
            Log.w(TAG, "Rate-limiting events for group $groupId")
            return ProcessResult(false)
        }

        // 5. Validate group membership
        val group = groupRepo.getById(groupId) ?: run {
            Log.w(TAG, "Rejecting event for unknown group $groupId")
            return ProcessResult(false)
        }

        val membershipResult = validateMembership(eventType, authorHex, group, knownGroupKey, groupId, inner)
        if (!membershipResult.allowed) return ProcessResult(false)

        // 6. Decrypt: use cached self-join decryption if available, else try epoch keys
        var decrypted: String? = membershipResult.cachedDecrypted
        var decryptedEpoch = group.keyEpoch
        if (decrypted == null) {
            val groupKey = knownGroupKey ?: groupRepo.getGroupKey(groupId) ?: return ProcessResult(false)
            decrypted = try {
                encryption.decrypt(inner.content, groupKey)
            } catch (_: Exception) {
                null
            }
            if (decrypted == null && group.keyEpoch > 0) {
                // Event may have been encrypted with an older epoch key (race condition)
                for (epoch in (group.keyEpoch - 1) downTo 0) {
                    val oldKey = groupRepo.getGroupKeyForEpoch(groupId, epoch) ?: continue
                    decrypted = try {
                        encryption.decrypt(inner.content, oldKey)
                    } catch (_: Exception) {
                        null
                    }
                    if (decrypted != null) {
                        decryptedEpoch = epoch
                        break
                    }
                }
            }
        }

        // key_rotation events are encrypted per-member with NIP-44 conversation keys
        if (decrypted == null && eventType == "key_rotation") {
            decrypted = try {
                val privKey = identity.getPrivateKeyBytes()
                try {
                    val creatorPubBytes = authorHex.hexToBytes()
                    val convKey = Nip44.getConversationKey(privKey, creatorPubBytes)
                    Nip44.decrypt(inner.content, convKey)
                } finally {
                    privKey.fill(0)
                }
            } catch (_: Exception) {
                null
            }
        }

        // There is no legitimate reason to store ciphertext we cannot read: the epoch
        // fallback loop above already tried every known group key.
        if (decrypted == null) {
            Log.w(TAG, "Rejecting undecryptable $eventType from $authorHex in group $groupId")
            return ProcessResult(false)
        }

        if (!eventValidator.isContentSafe(decrypted)) {
            Log.w(TAG, "Rejecting event with unsafe content: ${inner.id}")
            return ProcessResult(false)
        }

        // 7. Business rule validations
        if (!validateBusinessRules(eventType, authorHex, expenseUuid, groupId)) {
            return ProcessResult(false)
        }

        // 7b. Validate remote payloads before storing
        if (!validatePayload(eventType, decrypted, authorHex, expenseUuid, group.members.toSet(), inner.id)) {
            return ProcessResult(false)
        }

        // 8. Store
        // A gift-wrapped rumor is unsigned; the seal signature is the proof that `authorHex` wrote
        // it. Record it under a `seal:` marker so the row is distinguishable from an unverified one
        // (backup restore trusts it; BLE/self-heal forwarding, which peers must verify, skips it).
        val storedSig = if (unwrapResult != null) EventSnapshot.SEAL_SIG_PREFIX + unwrapResult.sealSig else inner.sig
        if (!eventDao.insertIfNew(
                EventEntity(
                    eventId = inner.id, groupId = groupId, pubkey = authorHex,
                    createdAt = inner.createdAt, kind = NostrKind.APP_SPECIFIC, contentEncrypted = inner.content,
                    eventType = eventType, expenseUuid = expenseUuid, sig = storedSig,
                    receivedAt = System.currentTimeMillis() / 1000, originalEventJson = inner.toJson(),
                    keyEpoch = decryptedEpoch
                )
            )
        ) {
            return ProcessResult(false)
        }

        // 9. Post-process (delegated)
        postProcessor.handle(eventType, decrypted, authorHex, groupId, inner.createdAt, nonCancellable)

        Log.i(TAG, "Stored $eventType from ${authorHex.take(8)} in group ${group.name} (${inner.id.take(8)})")
        return ProcessResult(true, group.name, eventType, decrypted, authorHex)
    }

    /** Result of membership validation, optionally carrying a cached decryption. */
    private data class MembershipResult(val allowed: Boolean, val cachedDecrypted: String? = null)

    private suspend fun validateMembership(
        eventType: String,
        authorHex: String,
        group: com.splitfree.domain.model.group.Group,
        knownGroupKey: String?,
        groupId: String,
        inner: NostrEvent
    ): MembershipResult {
        if (authorHex !in group.members) {
            if (eventType != "group_meta") {
                Log.w(TAG, "Rejecting $eventType from non-member $authorHex in group $groupId")
                return MembershipResult(false)
            }
        }
        if (eventType == "group_meta") {
            val isCreator = group.createdBy.isNotEmpty() && authorHex == group.createdBy
            if (!isCreator && authorHex !in group.members) {
                val key = knownGroupKey ?: groupRepo.getGroupKey(groupId) ?: ""
                val decryptedContent = try {
                    encryption.decrypt(inner.content, key)
                } catch (_: Exception) {
                    null
                }
                val isSelfJoin = try {
                    if (decryptedContent == null) {
                        false
                    } else {
                        val meta = json.decodeFromString<GroupMeta>(decryptedContent)
                        val current = group.members.toSet()
                        val proposed = meta.members.toSet()
                        (proposed - current) == setOf(authorHex) &&
                            (current - proposed).isEmpty() &&
                            meta.name == group.name &&
                            meta.relays.toSet() == group.relays.toSet()
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
        if (eventType == "key_rotation" && group.createdBy.isNotEmpty() && authorHex != group.createdBy) {
            Log.w(TAG, "Rejecting key_rotation from non-creator $authorHex in group $groupId")
            return MembershipResult(false)
        }
        return MembershipResult(true)
    }

    private suspend fun validateBusinessRules(
        eventType: String,
        authorHex: String,
        expenseUuid: String?,
        groupId: String
    ): Boolean {
        if (eventType == "expense_correction" || eventType == "expense_delete") {
            val originalCreator = expenseUuid?.let { eventDao.getExpenseByUuid(it, groupId)?.pubkey }
            if (!eventValidator.isCorrectionAuthorValid(eventType, authorHex, originalCreator)) {
                Log.w(TAG, "Rejecting $eventType: author $authorHex is not the original creator")
                return false
            }
        }
        if (eventType == "expense" && expenseUuid != null) {
            val deletedUuids = eventDao.getDeletedExpenseUuids(groupId).toSet()
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

    companion object {
        private const val TAG = "EventProcessor"
    }
}
