package com.splitfree.sync.event

import com.splitfree.data.identity.IdentityManager
import com.splitfree.data.local.dao.EventDao
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GiftWrapService
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.group.GroupMeta
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
    private val groupRepo: GroupRepository,
    private val encryption: GroupEncryption,
    private val signer: EventSigner,
    private val identity: IdentityManager,
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
        val inner = unwrapResult?.first ?: rawEvent
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

        // 4. Validate group membership
        val authorHex = inner.pubkey
        val group = groupRepo.getById(groupId) ?: run {
            Log.w(TAG, "Rejecting event for unknown group $groupId")
            return ProcessResult(false)
        }

        if (!validateMembership(eventType, authorHex, group, knownGroupKey, groupId, inner)) {
            return ProcessResult(false)
        }

        // 5. Rate limits
        if (!eventValidator.isWithinRateLimit(authorHex)) {
            Log.w(TAG, "Rate-limiting events from $authorHex")
            return ProcessResult(false)
        }
        if (!eventValidator.isWithinGroupRateLimit(groupId)) {
            Log.w(TAG, "Rate-limiting events for group $groupId")
            return ProcessResult(false)
        }

        // 6. Decrypt
        val groupKey = knownGroupKey ?: groupRepo.getGroupKey(groupId) ?: return ProcessResult(false)
        val decrypted = try {
            encryption.decrypt(inner.content, groupKey)
        } catch (_: Exception) {
            null
        }

        if (decrypted != null && !eventValidator.isContentSafe(decrypted)) {
            Log.w(TAG, "Rejecting event with unsafe content: ${inner.id}")
            return ProcessResult(false)
        }

        // 7. Business rule validations
        if (!validateBusinessRules(eventType, authorHex, expenseUuid, groupId, inner.createdAt)) {
            return ProcessResult(false)
        }

        // 8. Store
        if (!eventDao.insertIfNew(
                EventEntity(
                    eventId = inner.id, groupId = groupId, pubkey = authorHex,
                    createdAt = inner.createdAt, kind = 30078, contentEncrypted = inner.content,
                    eventType = eventType, expenseUuid = expenseUuid, sig = inner.sig,
                    receivedAt = System.currentTimeMillis() / 1000, originalEventJson = inner.toJson()
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

    private suspend fun validateMembership(
        eventType: String,
        authorHex: String,
        group: com.splitfree.domain.model.group.Group,
        knownGroupKey: String?,
        groupId: String,
        inner: NostrEvent
    ): Boolean {
        val privilegedTypes = setOf("group_meta", "group_migrate", "key_revocation")
        if (eventType !in privilegedTypes && authorHex !in group.members) {
            Log.w(TAG, "Rejecting event from non-member $authorHex in group $groupId")
            return false
        }
        if (eventType == "group_meta") {
            val isCreator = group.createdBy.isEmpty() || authorHex == group.createdBy
            if (!isCreator) {
                val isSelfJoin = try {
                    val key = knownGroupKey ?: groupRepo.getGroupKey(groupId) ?: ""
                    val meta = json.decodeFromString<GroupMeta>(encryption.decrypt(inner.content, key))
                    val current = group.members.toSet()
                    val proposed = meta.members.toSet()
                    (proposed - current) == setOf(authorHex) &&
                        (current - proposed).isEmpty() &&
                        meta.name == group.name &&
                        meta.relays.toSet() == group.relays.toSet()
                } catch (e: Exception) {
                    Log.w(TAG, "Self-join check failed for $authorHex in $groupId: ${e.message}")
                    false
                }
                if (!isSelfJoin) {
                    Log.w(TAG, "Rejecting group_meta from non-creator $authorHex in group $groupId")
                    return false
                }
            }
        }
        return true
    }

    private suspend fun validateBusinessRules(
        eventType: String,
        authorHex: String,
        expenseUuid: String?,
        groupId: String,
        createdAt: Long
    ): Boolean {
        if (eventType == "expense_correction" || eventType == "expense_delete") {
            val originalCreator = expenseUuid?.let { eventDao.getExpenseByUuid(it)?.pubkey }
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
        if (eventType == "expense" || eventType == "expense_correction") {
            val lastSettlement = eventDao.getLatestEventByType(groupId, "settlement")
            if (!eventValidator.isNotBackdatedBeforeSettlement(createdAt, lastSettlement?.createdAt)) {
                Log.w(TAG, "Rejecting backdated event: before last settlement")
                return false
            }
        }
        return true
    }

    companion object {
        private const val TAG = "EventProcessor"
    }
}
