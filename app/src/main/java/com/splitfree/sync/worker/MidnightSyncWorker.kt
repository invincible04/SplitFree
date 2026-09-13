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
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.usecase.expense.BalanceUnavailableException
import com.splitfree.domain.usecase.expense.CreateSnapshotUseCase
import com.splitfree.domain.usecase.sync.SelfHealUseCase
import com.splitfree.util.DebugLog as Log
import com.splitfree.util.ProcessHealthTracker
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Daily full sync worker scheduled at midnight.
 *
 * Unlike [SyncWorker], this pulls the complete event history (lenient timestamps)
 * and abandons outbox entries idle for [OUTBOX_RETENTION_DAYS]. Reschedules itself for
 * the next midnight after completion.
 */
@HiltWorker
class MidnightSyncWorker
@AssistedInject
constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val outboxDao: OutboxDao,
    private val groupRepo: GroupRepositoryContract,
    private val nostrClient: NostrClient,
    private val identity: IdentityContract,
    private val createSnapshot: CreateSnapshotUseCase,
    private val selfHeal: SelfHealUseCase,
    private val relayConnectionManager: RelayConnectionManager,
    private val syncEngine: SyncEngine
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        var acquired = false
        ProcessHealthTracker.heartbeat(applicationContext, "midnight_worker_start")
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
                snapshotIfReadable(group.id)
            }
            // Cleanup outbox rows with no publish activity for 90 days (self-heal has covered
            // them by now). Keyed off the last attempt, not event time; see OutboxDao.deleteOlderThan.
            outboxDao.deleteOlderThan(System.currentTimeMillis() / 1000 - OUTBOX_RETENTION_DAYS * 86400)
            reschedule()
            ProcessHealthTracker.heartbeat(applicationContext, "midnight_worker_success")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Midnight sync failed: ${e.message}")
            ProcessHealthTracker.heartbeat(applicationContext, "midnight_worker_fail", e.javaClass.simpleName)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        } finally {
            if (acquired) nostrClient.releaseConnection()
        }
    }

    /**
     * A snapshot is an optimisation: a group whose money events this device cannot read yet is logged and
     * skipped so the remaining groups still sync, the outbox is still trimmed and the run succeeds.
     */
    private suspend fun snapshotIfReadable(groupId: String) {
        try {
            createSnapshot(groupId)
        } catch (e: BalanceUnavailableException) {
            Log.w(TAG, "Skipping snapshot for group $groupId: ${e.message}")
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

        /** Outbox rows idle (no publish attempt) for this long are abandoned. */
        const val OUTBOX_RETENTION_DAYS = 90L
    }
}
