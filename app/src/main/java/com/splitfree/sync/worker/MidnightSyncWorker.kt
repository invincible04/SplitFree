package com.splitfree.sync.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.splitfree.data.local.dao.OutboxDao
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.nostr.relay.RelayConnectionManager
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.IdentityManager
import com.splitfree.domain.usecase.expense.CreateSnapshotUseCase
import com.splitfree.domain.usecase.sync.SelfHealUseCase
import com.splitfree.util.DebugLog as Log
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Daily full sync worker scheduled at midnight.
 *
 * Unlike [SyncWorker], this pulls the complete event history (lenient timestamps)
 * and cleans up stale outbox entries older than 30 days. Reschedules itself for
 * the next midnight after completion.
 */
@HiltWorker
class MidnightSyncWorker
@AssistedInject
constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val outboxDao: OutboxDao,
    private val groupRepo: GroupRepository,
    private val nostrClient: NostrClient,
    private val identity: IdentityManager,
    private val createSnapshot: CreateSnapshotUseCase,
    private val selfHeal: SelfHealUseCase,
    private val relayConnectionManager: RelayConnectionManager,
    private val syncEngine: SyncEngine
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        var acquired = false
        return try {
            if (!identity.hasIdentity()) return Result.success()
            relayConnectionManager.ensureConnected(forceReconnect = true)
            acquired = true
            syncEngine.flushOutbox()
            for (group in groupRepo.getAll()) {
                val groupKey = groupRepo.getGroupKey(group.id) ?: continue
                val count = syncEngine.pullEvents(group.id, 0, groupKey, lenientTimestamp = true)
                if (count > 0) Log.i(TAG, "Midnight sync pulled $count events for ${group.name}")
                selfHeal(group.id)
                createSnapshot(group.id)
            }
            // Cleanup old outbox entries (self-heal has covered them by now)
            outboxDao.deleteOlderThan(System.currentTimeMillis() / 1000 - 30 * 86400)
            reschedule()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Midnight sync failed: ${e.message}")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        } finally {
            if (acquired) nostrClient.releaseConnection()
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
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        private const val TAG = "MidnightSyncWorker"
        const val WORK_NAME = "splitfree_midnight_sync"
    }
}
