package com.splitfree.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.splitfree.data.local.EventDao
import com.splitfree.data.local.OutboxDao
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.EventValidator
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.IdentityManager
import com.splitfree.domain.model.GroupMeta
import com.splitfree.domain.usecase.CreateSnapshotUseCase
import com.splitfree.domain.usecase.MigrateGroupUseCase
import com.splitfree.domain.usecase.RevokeKeyUseCase
import com.splitfree.domain.usecase.SelfHealUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.serialization.json.Json

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val outboxDao: OutboxDao,
    private val eventDao: EventDao,
    private val groupRepo: GroupRepository,
    private val nostrClient: NostrClient,
    private val identity: IdentityManager,
    private val encryption: GroupEncryption,
    private val signer: EventSigner,
    private val createSnapshot: CreateSnapshotUseCase,
    private val selfHeal: SelfHealUseCase,
    private val migrateGroup: MigrateGroupUseCase,
    private val revokeKey: RevokeKeyUseCase,
    private val giftWrap: com.splitfree.domain.crypto.GiftWrapService,
    private val relayHealthMonitor: com.splitfree.data.nostr.RelayHealthMonitor
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        var acquiredConnection = false
        return try {
            if (!identity.hasIdentity()) return Result.success()
            acquiredConnection = ensureConnected()
            publishOutbox()
            pullFromRelays()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed: ${e.message}")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        } finally {
            if (acquiredConnection) nostrClient.releaseConnection()
        }
    }

    private suspend fun ensureConnected(): Boolean {
        if (!nostrClient.isConnected) {
            nostrClient.authSigner = { challenge, relayUrl -> createAuthEvent(challenge, relayUrl) }
            val allRelays = groupRepo.getAll()
                .flatMap { it.relays }
                .distinct()
                .ifEmpty { DEFAULT_RELAYS }
            val onlineRelays = relayHealthMonitor.getOnlineRelays(allRelays)
                .ifEmpty { allRelays } // fallback to all if none checked yet
            nostrClient.connect(onlineRelays)
        }
        nostrClient.acquireConnection()
        return true
    }

    private fun createAuthEvent(challenge: String, relayUrl: String): com.splitfree.domain.crypto.NostrEvent {
        val privKey = identity.getPrivateKeyBytes()
        try {
            return com.splitfree.domain.crypto.NostrEvent(
                pubkey = identity.getPublicKeyHex(),
                createdAt = System.currentTimeMillis() / 1000,
                kind = 22242,
                tags = listOf(listOf("challenge", challenge), listOf("relay", relayUrl)),
                content = ""
            ).sign(privKey)
        } finally {
            privKey.fill(0)
        }
    }

    private suspend fun publishOutbox() {
        // Prune events older than 7 days — relays may reject stale timestamps
        val sevenDaysAgo = System.currentTimeMillis() / 1000 - 7 * 86400
        outboxDao.deleteOlderThan(sevenDaysAgo)

        val pending = outboxDao.getAll()
        for (event in pending) {
            val success = nostrClient.publishJson(event.eventJson)
            if (success) {
                outboxDao.delete(event.eventId)
            } else {
                if (event.retryCount >= MAX_RETRIES) {
                    Log.w(TAG, "Dropping event ${event.eventId} after $MAX_RETRIES retries")
                    outboxDao.delete(event.eventId)
                } else {
                    outboxDao.incrementRetry(event.eventId, System.currentTimeMillis() / 1000)
                }
            }
        }
    }

    private suspend fun pullFromRelays() {
        val groups = groupRepo.getAll()
        for (group in groups) {
            val groupEntity = groupRepo.getGroupEntity(group.id) ?: continue
            val since = groupEntity.lastSyncTimestamp - 3600

            val events = nostrClient.fetchEvents(group.id, since)
            val existingIds = eventDao.getEventIds(group.id).toSet()
            var newCount = 0

            val groupKey = groupRepo.getGroupKey(group.id) ?: continue
            for (event in events) {
                val eventId = event.id
                if (eventId in existingIds) continue
                if (!verifyAndStore(event, group.id, groupKey)) continue
                newCount++
            }

            if (newCount > 0) {
                groupRepo.updateLastSync(group.id, System.currentTimeMillis() / 1000)
                Log.i(TAG, "Pulled $newCount new events for group ${group.name}")
            }

            createSnapshot(group.id)
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun verifyAndStore(event: com.splitfree.domain.crypto.NostrEvent, groupId: String, groupKey: String): Boolean {
        return try {
            val unwrapResult = giftWrap.tryUnwrap(event)
            val inner = unwrapResult?.first ?: event
            if (!signer.verify(inner)) return false

            if (!EventValidator.isTimestampValid(inner.createdAt)) {
                Log.w(TAG, "Rejecting event with invalid timestamp: ${inner.id}")
                return false
            }

            val authorHex = inner.pubkey
            val group = groupRepo.getById(groupId)

            if (!EventValidator.isWithinRateLimit(authorHex)) {
                Log.w(TAG, "Rate-limiting events from $authorHex")
                return false
            }

            val encrypted = inner.content
            val decrypted = try { encryption.decrypt(encrypted, groupKey) } catch (_: Exception) { null }

            // Reject oversized or deeply nested content before deserialization (DoS prevention)
            if (decrypted != null && !EventValidator.isContentSafe(decrypted)) {
                Log.w(TAG, "Rejecting event with unsafe content: ${inner.id}")
                return false
            }

            var eventType = "unknown"
            var expenseUuid: String? = null
            for (tag in inner.tags) {
                if (tag.size >= 2) {
                    when (tag[0]) {
                        "t" -> eventType = tag[1]
                        "e" -> expenseUuid = tag[1]
                    }
                }
            }

            // Validate corrections/deletions come from the original expense creator
            if (eventType == "expense_correction" || eventType == "expense_delete") {
                val originalCreator = expenseUuid?.let { eventDao.getExpenseByUuid(it)?.pubkey }
                if (!EventValidator.isCorrectionAuthorValid(eventType, authorHex, originalCreator)) {
                    Log.w(TAG, "Rejecting ${eventType} ${inner.id}: author $authorHex is not the original creator")
                    return false
                }
            }

            // Reject replayed expenses that were previously deleted (tombstone check)
            if (eventType == "expense" && expenseUuid != null) {
                val deletedUuids = eventDao.getDeletedExpenseUuids(groupId).toSet()
                if (EventValidator.isDeletedExpense(eventType, expenseUuid, deletedUuids)) {
                    Log.w(TAG, "Rejecting replayed deleted expense: $expenseUuid")
                    return false
                }
            }

            // Reject expense events backdated before the last settlement (timestamp manipulation defense)
            if (eventType == "expense" || eventType == "expense_correction") {
                val lastSettlement = eventDao.getLatestEventByType(groupId, "settlement")
                if (!EventValidator.isNotBackdatedBeforeSettlement(inner.createdAt, lastSettlement?.createdAt)) {
                    Log.w(TAG, "Rejecting backdated event ${inner.id}: before last settlement")
                    return false
                }
            }

            if (eventType != "group_meta" && eventType != "group_migrate" && eventType != "key_revocation" && group != null && authorHex !in group.members) {
                Log.w(TAG, "Rejecting event from non-member $authorHex in group $groupId")
                return false
            }

            // group_meta requires membership; group_migrate/key_revocation validated downstream
            if (eventType == "group_meta" && group != null && authorHex !in group.members) {
                Log.w(TAG, "Rejecting group_meta from non-member $authorHex in group $groupId")
                return false
            }

            // Only the group creator can publish group_meta updates
            if (eventType == "group_meta" && group != null && !EventValidator.isGroupMetaAuthorValid(authorHex, group.createdBy)) {
                Log.w(TAG, "Rejecting group_meta from non-creator $authorHex in group $groupId")
                return false
            }

            // Atomic insert — prevents TOCTOU race with concurrent sync paths
            if (!eventDao.insertIfNew(EventEntity(
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
                    originalEventJson = inner.toJson()
                )
            )) return false // already existed

            if (eventType == "group_meta" && decrypted != null) {
                try {
                    val meta = json.decodeFromString<GroupMeta>(decrypted)
                    if (meta.members.isNotEmpty()) {
                        groupRepo.updateFromMeta(groupId, meta.name, meta.members, meta.relays)
                    }
                } catch (_: Exception) {}
            }

            if (eventType == "group_migrate" && decrypted != null) {
                try { migrateGroup.handleMigration(decrypted, authorHex, groupId) } catch (_: Exception) {}
            }

            if (eventType == "key_revocation" && decrypted != null) {
                try { revokeKey.handleRevocation(decrypted, authorHex, groupId) } catch (_: Exception) {}
            }

            ExpenseNotifier.notifyIfNeeded(
                applicationContext, eventType, decrypted, authorHex,
                identity.getPublicKeyHex(), group?.name ?: "Group"
            )

            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to process event: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val MAX_RETRIES = 5
        val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.nostr.band",
            "wss://relay.snort.social",
            "wss://nostr.wine"
        )
    }
}
