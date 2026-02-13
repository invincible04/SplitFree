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
import com.splitfree.domain.crypto.IdentityManager
import com.splitfree.domain.usecase.CreateSnapshotUseCase
import com.splitfree.domain.usecase.SelfHealUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Full sync at midnight per design doc Section 12.2.
 * Pulls ALL events, runs self-heal unconditionally, creates snapshots, flushes outbox.
 */
@HiltWorker
class MidnightSyncWorker
    @AssistedInject
    constructor(
        @Assisted private val context: Context,
        @Assisted params: WorkerParameters,
        private val outboxDao: OutboxDao,
        private val eventDao: EventDao,
        private val groupRepo: GroupRepository,
        private val nostrClient: NostrClient,
        private val identity: IdentityManager,
        private val createSnapshot: CreateSnapshotUseCase,
        private val selfHeal: SelfHealUseCase,
        private val eventProcessor: EventProcessor,
        private val signer: EventSigner,
    ) : CoroutineWorker(context, params) {
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
                nostrClient.authSigner = { challenge, relayUrl -> signer.createAuthEvent(challenge, relayUrl) }
                val relays =
                    groupRepo
                        .getAll()
                        .flatMap { it.relays }
                        .distinct()
                        .ifEmpty { SyncWorker.DEFAULT_RELAYS }
                nostrClient.connect(relays)
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
                val groupKey = groupRepo.getGroupKey(group.id) ?: continue
                val events = nostrClient.fetchEvents(group.id, 0)
                val existingIds = eventDao.getEventIds(group.id).toSet()
                var count = 0
                for (event in events) {
                    if (event.id in existingIds) continue
                    val result =
                        eventProcessor.process(
                            rawEvent = event,
                            knownGroupId = group.id,
                            knownGroupKey = groupKey,
                            lenientTimestamp = true,
                        )
                    if (result.stored) count++
                }
                if (count > 0) {
                    groupRepo.updateLastSync(group.id, System.currentTimeMillis() / 1000)
                    Log.i(TAG, "Midnight sync pulled $count events for ${group.name}")
                }
                selfHeal(group.id)
                createSnapshot(group.id)
            }
        }

        private fun reschedule() {
            val now = Calendar.getInstance()
            val next =
                Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                }
            val delay = next.timeInMillis - now.timeInMillis
            val request =
                OneTimeWorkRequestBuilder<MidnightSyncWorker>()
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        companion object {
            private const val TAG = "MidnightSyncWorker"
            const val WORK_NAME = "splitfree_midnight_sync"
        }
    }
