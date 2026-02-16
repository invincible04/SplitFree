package com.splitfree.sync

import android.content.Context
import com.splitfree.util.DebugLog as Log
import com.splitfree.data.local.EventDao
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.*
import com.splitfree.domain.model.GroupMeta
import com.splitfree.domain.usecase.MigrateGroupUseCase
import com.splitfree.domain.usecase.RevokeKeyUseCase
import com.splitfree.domain.usecase.SelfHealUseCase
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared event processing pipeline used by both ForegroundSyncService and SyncWorker.
 * Single source of truth for validation, storage, and post-processing of incoming events.
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
        private val migrateGroup: MigrateGroupUseCase,
        private val revokeKey: RevokeKeyUseCase,
        private val selfHeal: SelfHealUseCase,
    ) {
        private val json = Json { ignoreUnknownKeys = true }

        data class ProcessResult(
            val stored: Boolean,
            val groupName: String? = null,
            val eventType: String? = null,
            val decrypted: String? = null,
            val authorHex: String? = null,
        )

        /**
         * Process an incoming Nostr event: unwrap, validate, decrypt, store, and handle side effects.
         *
         * @param rawEvent The event as received from the relay (may be gift-wrapped)
         * @param knownGroupId If the caller already knows the group ID (e.g., SyncWorker pull), pass it.
         *                     Otherwise extracted from event tags.
         * @param knownGroupKey If the caller already has the group key, pass it to avoid a second lookup.
         * @param nonCancellable If true, wraps metadata updates in NonCancellable context
         *                       (needed for ForegroundSyncService coroutine scope).
         * @param lenientTimestamp If true, uses lenient timestamp validation (allows any age).
         *                         Use for full/initial sync where historical events are expected.
         * @return ProcessResult indicating whether the event was stored and metadata for notifications.
         */
        suspend fun process(
            rawEvent: NostrEvent,
            knownGroupId: String? = null,
            knownGroupKey: String? = null,
            nonCancellable: Boolean = false,
            lenientTimestamp: Boolean = false,
        ): ProcessResult {
            val unwrapResult = giftWrap.tryUnwrap(rawEvent)
            val inner = unwrapResult?.first ?: rawEvent
            // Rumors from NIP-59 unwrap are unsigned by spec; the seal signature
            // (verified inside Nip59.unwrap) already authenticates the sender.
            if (unwrapResult == null && !signer.verify(inner)) return ProcessResult(false)

            val timestampValid =
                if (lenientTimestamp) {
                    EventValidator.isTimestampValidLenient(inner.createdAt)
                } else {
                    EventValidator.isTimestampValid(inner.createdAt)
                }
            if (!timestampValid) {
                Log.w(TAG, "Rejecting event with invalid timestamp: ${inner.id}")
                return ProcessResult(false)
            }

            // Extract tags
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

            val authorHex = inner.pubkey
            val group = groupRepo.getById(groupId)
            if (group == null) {
                Log.w(TAG, "Rejecting event for unknown group $groupId")
                return ProcessResult(false)
            }

            // Membership checks
            val privilegedTypes = setOf("group_meta", "group_migrate", "key_revocation")
            if (eventType !in privilegedTypes && authorHex !in group.members) {
                Log.w(TAG, "Rejecting event from non-member $authorHex in group $groupId")
                return ProcessResult(false)
            }
            if (eventType == "group_meta") {
                val isCreator = group.createdBy.isEmpty() || authorHex == group.createdBy
                if (!isCreator) {
                    // Non-creators may only publish a self-join: their pubkey must be
                    // in the new member list and the name must not change.
                    val isSelfJoin = try {
                        val key = knownGroupKey ?: groupRepo.getGroupKey(groupId) ?: ""
                        val meta = json.decodeFromString<GroupMeta>(
                            encryption.decrypt(inner.content, key)
                        )
                        authorHex in meta.members
                    } catch (e: Exception) {
                        Log.w(TAG, "Self-join check failed for $authorHex in $groupId: ${e.message}")
                        false
                    }
                    if (!isSelfJoin) {
                        Log.w(TAG, "Rejecting group_meta from non-creator $authorHex in group $groupId")
                        return ProcessResult(false)
                    }
                }
            }

            // Rate limits
            if (!EventValidator.isWithinRateLimit(authorHex)) {
                Log.w(TAG, "Rate-limiting events from $authorHex")
                return ProcessResult(false)
            }
            if (!EventValidator.isWithinGroupRateLimit(groupId)) {
                Log.w(TAG, "Rate-limiting events for group $groupId")
                return ProcessResult(false)
            }

            // Decrypt
            val groupKey = knownGroupKey ?: groupRepo.getGroupKey(groupId) ?: return ProcessResult(false)
            val encrypted = inner.content
            val decrypted =
                try {
                    encryption.decrypt(encrypted, groupKey)
                } catch (_: Exception) {
                    null
                }

            if (decrypted != null && !EventValidator.isContentSafe(decrypted)) {
                Log.w(TAG, "Rejecting event with unsafe content: ${inner.id}")
                return ProcessResult(false)
            }

            // Correction/deletion author validation
            if (eventType == "expense_correction" || eventType == "expense_delete") {
                val originalCreator = expenseUuid?.let { eventDao.getExpenseByUuid(it)?.pubkey }
                if (!EventValidator.isCorrectionAuthorValid(eventType, authorHex, originalCreator)) {
                    Log.w(TAG, "Rejecting $eventType ${inner.id}: author $authorHex is not the original creator")
                    return ProcessResult(false)
                }
            }

            // Tombstone check
            if (eventType == "expense" && expenseUuid != null) {
                val deletedUuids = eventDao.getDeletedExpenseUuids(groupId).toSet()
                if (EventValidator.isDeletedExpense(eventType, expenseUuid, deletedUuids)) {
                    Log.w(TAG, "Rejecting replayed deleted expense: $expenseUuid")
                    return ProcessResult(false)
                }
            }

            // Backdating check
            if (eventType == "expense" || eventType == "expense_correction") {
                val lastSettlement = eventDao.getLatestEventByType(groupId, "settlement")
                if (!EventValidator.isNotBackdatedBeforeSettlement(inner.createdAt, lastSettlement?.createdAt)) {
                    Log.w(TAG, "Rejecting backdated event ${inner.id}: before last settlement")
                    return ProcessResult(false)
                }
            }

            // Atomic insert
            if (!eventDao.insertIfNew(
                    EventEntity(
                        eventId = inner.id,
                        groupId = groupId,
                        pubkey = authorHex,
                        createdAt = inner.createdAt,
                        kind = 30078,
                        contentEncrypted = encrypted,
                        contentDecrypted = decrypted,
                        eventType = eventType,
                        expenseUuid = expenseUuid,
                        sig = inner.sig,
                        receivedAt = System.currentTimeMillis() / 1000,
                        originalEventJson = inner.toJson(),
                    ),
                )
            ) {
                return ProcessResult(false)
            }

            // Post-processing side effects
            handleSideEffects(eventType, decrypted, authorHex, groupId, inner.createdAt, nonCancellable)

            Log.i(TAG, "Stored $eventType from ${authorHex.take(8)} in group ${group.name} (${inner.id.take(8)})")
            return ProcessResult(
                stored = true,
                groupName = group.name,
                eventType = eventType,
                decrypted = decrypted,
                authorHex = authorHex,
            )
        }

        private suspend fun handleSideEffects(
            eventType: String,
            decrypted: String?,
            authorHex: String,
            groupId: String,
            createdAt: Long,
            nonCancellable: Boolean,
        ) {
            if (decrypted == null) return

            suspend fun <T> maybeNonCancellable(block: suspend () -> T): T =
                if (nonCancellable) withContext(NonCancellable) { block() } else block()

            when (eventType) {
                "group_meta" -> {
                    try {
                        maybeNonCancellable {
                            val meta = json.decodeFromString<GroupMeta>(decrypted)
                            if (meta.members.isNotEmpty()) {
                                val currentGroup = groupRepo.getById(groupId)
                                val relaysChanged = currentGroup != null && currentGroup.relays.toSet() != meta.relays.toSet()
                                val isCreator = currentGroup == null || currentGroup.createdBy.isEmpty() || authorHex == currentGroup.createdBy

                                // Non-creator events merge members; creator events replace
                                val finalMembers = if (isCreator) {
                                    meta.members
                                } else {
                                    ((currentGroup?.members ?: emptyList()) + meta.members).distinct()
                                }

                                Log.i(TAG, "Applying group_meta for $groupId: ${finalMembers.size} members, name=${meta.name}")
                                groupRepo.updateFromMeta(groupId, meta.name, finalMembers, meta.relays, createdAt)

                                // Trigger eager self-heal when relays change
                                if (relaysChanged) {
                                    Log.i(TAG, "Relays changed for $groupId — triggering eager self-heal")
                                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                        try { selfHeal(groupId) } catch (e: Exception) {
                                            Log.w(TAG, "Eager self-heal failed for $groupId: ${e.message}")
                                        }
                                    }
                                }
                            }
                        }
                    } catch (
                        e: kotlinx.coroutines.CancellationException,
                    ) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to process group_meta for $groupId: ${e.message}", e)
                    }
                }

                "group_migrate" -> {
                    try {
                        maybeNonCancellable { migrateGroup.handleMigration(decrypted, authorHex, groupId) }
                    } catch (
                        e: kotlinx.coroutines.CancellationException,
                    ) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to process group_migrate for $groupId: ${e.message}", e)
                    }
                }

                "key_revocation" -> {
                    try {
                        maybeNonCancellable { revokeKey.handleRevocation(decrypted, authorHex, groupId) }
                    } catch (
                        e: kotlinx.coroutines.CancellationException,
                    ) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to process key_revocation for $groupId: ${e.message}", e)
                    }
                }
            }
        }

        companion object {
            private const val TAG = "EventProcessor"
        }
    }
