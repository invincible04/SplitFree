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
    private val selfHeal: SelfHealUseCase
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
            val relays = groupRepo.getAll().flatMap { it.relays }.distinct()
                .ifEmpty { SyncWorker.DEFAULT_RELAYS }
            nostrClient.connect(identity.getKeys(), relays)
        }
        nostrClient.acquireConnection()
        return true
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
            val groupEntity = groupRepo.getGroupEntity(group.id) ?: continue
            // Full pull — since=0
            val events = nostrClient.fetchEvents(group.id, 0)
            val existingIds = eventDao.getEventIds(group.id).toSet()
            var count = 0
            for (event in events) {
                if (event.id().toHex() in existingIds) continue
                if (verifyAndStore(event, group.id, groupEntity.groupKey)) count++
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

    private suspend fun verifyAndStore(event: rust.nostr.sdk.Event, groupId: String, groupKey: String): Boolean {
        return try {
            // Try to unwrap gift wrap first (matches SyncWorker behavior)
            val inner = giftWrap.tryUnwrap(event) ?: event
            if (!signer.verify(inner)) return false

            // Reject future/stale timestamps (design doc Section 13.7)
            if (!EventValidator.isTimestampValid(inner.createdAt().asSecs().toLong())) {
                Log.w(TAG, "Rejecting event with invalid timestamp: ${inner.id().toHex()}")
                return false
            }

            val encrypted = inner.content()
            val decrypted = try { encryption.decrypt(encrypted, groupKey) } catch (_: Exception) { null }
            var eventType = "unknown"
            var expenseUuid: String? = null
            for (tag in inner.tags().toVec()) {
                val items = tag.asVec()
                if (items.size >= 2) when (items[0]) {
                    "t" -> eventType = items[1]
                    "e" -> expenseUuid = items[1]
                }
            }

            // Reject events from non-members (design doc Section 13.2)
            val authorHex = inner.author().toHex()
            val group = groupRepo.getById(groupId)
            if (eventType != "group_meta" && group != null && authorHex !in group.members) {
                Log.w(TAG, "Rejecting event from non-member $authorHex in group $groupId")
                return false
            }

            eventDao.insert(EventEntity(
                eventId = inner.id().toHex(), groupId = groupId,
                pubkey = authorHex,
                createdAt = inner.createdAt().asSecs().toLong(),
                kind = 30078, contentEncrypted = encrypted,
                contentDecrypted = decrypted, eventType = eventType,
                expenseUuid = expenseUuid, sig = inner.signature().toHex(),
                receivedAt = System.currentTimeMillis() / 1000,
                originalEventJson = inner.asJson()
            ))

            // Update local group from incoming group_meta events (matches SyncWorker behavior)
            if (eventType == "group_meta" && decrypted != null) {
                try {
                    val meta = json.decodeFromString<GroupMeta>(decrypted)
                    if (meta.members.isNotEmpty()) {
                        groupRepo.updateFromMeta(groupId, meta.name, meta.members, meta.relays)
                    }
                } catch (_: Exception) {}
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
