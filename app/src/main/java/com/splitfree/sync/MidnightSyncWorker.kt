package com.splitfree.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.splitfree.data.local.EventDao
import com.splitfree.data.local.OutboxDao
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.EventValidator
import com.splitfree.domain.crypto.GiftWrapService
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.IdentityManager
import com.splitfree.domain.model.GroupMeta
import com.splitfree.domain.usecase.CreateSnapshotUseCase
import com.splitfree.domain.usecase.MigrateGroupUseCase
import com.splitfree.domain.usecase.RevokeKeyUseCase
import com.splitfree.domain.usecase.SelfHealUseCase
import com.splitfree.data.local.entities.EventEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.serialization.json.Json
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Full sync at midnight per design doc Section 12.2.
 * Pulls ALL events, runs self-heal unconditionally, creates snapshots, flushes outbox.
 */
@HiltWorker
class MidnightSyncWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val outboxDao: OutboxDao,
    private val eventDao: EventDao,
    private val groupRepo: GroupRepository,
    private val nostrClient: NostrClient,
    private val identity: IdentityManager,
    private val encryption: GroupEncryption,
    private val signer: EventSigner,
    private val giftWrap: GiftWrapService,
    private val createSnapshot: CreateSnapshotUseCase,
    private val selfHeal: SelfHealUseCase,
    private val migrateGroup: MigrateGroupUseCase,
    private val revokeKey: RevokeKeyUseCase
) : CoroutineWorker(context, params) {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun doWork(): Result {
        var acquired = false
        return try {
            if (!identity.hasIdentity()) return Result.success()
            acquired = ensureConnected()
            flushOutbox()
            fullSync()
            reschedule()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Midnight sync failed: ${e.message}")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        } finally {
            if (acquired) nostrClient.releaseConnection()
        }
    }

    private suspend fun ensureConnected(): Boolean {
        if (!nostrClient.isConnected) {
            nostrClient.authSigner = { challenge, relayUrl -> createAuthEvent(challenge, relayUrl) }
            val relays = groupRepo.getAll().flatMap { it.relays }.distinct()
                .ifEmpty { SyncWorker.DEFAULT_RELAYS }
            nostrClient.connect(relays)
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

    private suspend fun flushOutbox() {
        for (event in outboxDao.getAll()) {
            if (nostrClient.publishJson(event.eventJson)) {
                outboxDao.delete(event.eventId)
            }
        }
    }

    private suspend fun fullSync() {
        val groups = groupRepo.getAll()
        for (group in groups) {
            val groupKey = groupRepo.getGroupKey(group.id) ?: continue
            // Full pull — since=0
            val events = nostrClient.fetchEvents(group.id, 0)
            val existingIds = eventDao.getEventIds(group.id).toSet()
            var count = 0
            for (event in events) {
                if (event.id in existingIds) continue
                if (verifyAndStore(event, group.id, groupKey)) count++
            }
            if (count > 0) {
                groupRepo.updateLastSync(group.id, System.currentTimeMillis() / 1000)
                Log.i(TAG, "Midnight sync pulled $count events for ${group.name}")
            }
            // Unconditional self-heal and snapshot
            selfHeal(group.id)
            createSnapshot(group.id)
        }
    }

    private suspend fun verifyAndStore(event: com.splitfree.domain.crypto.NostrEvent, groupId: String, groupKey: String): Boolean {
        return try {
            val unwrapResult = giftWrap.tryUnwrap(event)
            val inner = unwrapResult?.first ?: event
            if (!signer.verify(inner)) return false

            if (!EventValidator.isTimestampValidLenient(inner.createdAt)) {
                Log.w(TAG, "Rejecting event with invalid timestamp: ${inner.id}")
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
                if (tag.size >= 2) when (tag[0]) {
                    "t" -> eventType = tag[1]
                    "e" -> expenseUuid = tag[1]
                }
            }

            val authorHex = inner.pubkey

            if (!EventValidator.isWithinRateLimit(authorHex)) {
                Log.w(TAG, "Rate-limiting events from $authorHex")
                return false
            }

            if (!EventValidator.isWithinGroupRateLimit(groupId)) {
                Log.w(TAG, "Rate-limiting events for group $groupId")
                return false
            }

            // Validate corrections/deletions come from the original expense creator
            if (eventType == "expense_correction" || eventType == "expense_delete") {
                val originalCreator = expenseUuid?.let { eventDao.getExpenseByUuid(it)?.pubkey }
                if (!EventValidator.isCorrectionAuthorValid(eventType, authorHex, originalCreator)) {
                    Log.w(TAG, "Rejecting ${eventType} ${inner.id}: author $authorHex is not the original creator")
                    return false
                }
            }

            val group = groupRepo.getById(groupId)

            if (group == null) {
                Log.w(TAG, "Rejecting event for unknown group $groupId")
                return false
            }

            if (eventType != "group_meta" && eventType != "group_migrate" && eventType != "key_revocation" && authorHex !in group.members) {
                Log.w(TAG, "Rejecting event from non-member $authorHex in group $groupId")
                return false
            }

            // group_meta requires membership and creator authorization
            if (eventType == "group_meta" && authorHex !in group.members) {
                Log.w(TAG, "Rejecting group_meta from non-member $authorHex in group $groupId")
                return false
            }
            if (eventType == "group_meta" && !EventValidator.isGroupMetaAuthorValid(authorHex, group.createdBy)) {
                Log.w(TAG, "Rejecting group_meta from non-creator $authorHex in group $groupId")
                return false
            }

            // Atomic insert — prevents TOCTOU race with concurrent sync paths
            if (!eventDao.insertIfNew(EventEntity(
                eventId = inner.id, groupId = groupId,
                pubkey = authorHex,
                createdAt = inner.createdAt,
                kind = 30078, contentEncrypted = encrypted,
                contentDecrypted = decrypted, eventType = eventType,
                expenseUuid = expenseUuid, sig = inner.sig,
                receivedAt = System.currentTimeMillis() / 1000,
                originalEventJson = if (unwrapResult != null) event.toJson() else inner.toJson()
            ))) return false // already existed

            if (eventType == "group_meta" && decrypted != null) {
                try {
                    val meta = json.decodeFromString<GroupMeta>(decrypted)
                    if (meta.members.isNotEmpty()) {
                        groupRepo.updateFromMeta(groupId, meta.name, meta.members, meta.relays, inner.createdAt)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process group_meta for $groupId: ${e.message}", e)
                }
            }

            if (eventType == "group_migrate" && decrypted != null) {
                try { migrateGroup.handleMigration(decrypted, authorHex, groupId) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { Log.e(TAG, "Failed to process group_migrate for $groupId: ${e.message}", e) }
            }

            if (eventType == "key_revocation" && decrypted != null) {
                try { revokeKey.handleRevocation(decrypted, authorHex, groupId) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { Log.e(TAG, "Failed to process key_revocation for $groupId: ${e.message}", e) }
            }

            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to process event: ${e.message}")
            false
        }
    }

    private fun reschedule() {
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }
        val delay = next.timeInMillis - now.timeInMillis
        val request = OneTimeWorkRequestBuilder<MidnightSyncWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME, ExistingWorkPolicy.REPLACE, request
        )
    }

    companion object {
        private const val TAG = "MidnightSyncWorker"
        const val WORK_NAME = "splitfree_midnight_sync"
    }
}
