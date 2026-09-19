package com.splitfree.sync.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
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
import kotlinx.coroutines.CancellationException

/**
 * Daily full reconciliation, scheduled by [SyncScheduler.scheduleDailySync]: first run at the next
 * local midnight, then roughly every 24 hours; WorkManager may drift and defer it.
 *
 * Unlike [SyncWorker], this forces a fresh relay connection, resumes the same bounded history sweeps
 * as ordinary sync, self-heals, snapshots, and abandons outbox entries idle for
 * [OUTBOX_RETENTION_DAYS]. The run succeeds only when every attempted outbox row published and
 * every pull was complete; otherwise, or on an exception, it retries up to [RUN_RETRY_BUDGET] times
 * and then fails, and the next period runs regardless. Cancellation is rethrown, never reported.
 */
@HiltWorker
class DailySyncWorker
@AssistedInject
constructor(
    @Assisted context: Context,
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
        ProcessHealthTracker.heartbeat(applicationContext, "daily_worker_start")
        return try {
            if (!identity.hasIdentity()) return Result.success()
            relayConnectionManager.ensureConnected(forceReconnect = true)
            acquired = true
            var clean = true
            val flush = syncEngine.flushOutbox()
            if (flush.failed > 0) {
                Log.w(TAG, "${flush.failed} outbox row(s) failed to publish")
                clean = false
            }
            for (group in groupRepo.getAll()) {
                val groupKey = groupRepo.getGroupKey(group.id) ?: continue
                val pull = syncEngine.pullEvents(group.id, 0, groupKey, lenientTimestamp = true)
                if (pull.stored > 0) Log.i(TAG, "Daily sync pulled ${pull.stored} events for ${group.name}")
                if (!pull.complete) {
                    Log.w(TAG, "Incomplete full pull for group ${group.name}; run will retry")
                    clean = false
                }
                // Still heal and snapshot on a partial pass: they work from what is stored locally, and
                // skipping them would leave relays missing our events until a fully clean day.
                selfHeal(group.id)
                snapshotIfReadable(group.id)
            }
            // Cleanup outbox rows with no publish activity for 90 days (self-heal has covered
            // them by now). Keyed off the last attempt, not event time; see OutboxDao.deleteOlderThan.
            outboxDao.deleteOlderThan(System.currentTimeMillis() / 1000 - OUTBOX_RETENTION_DAYS * 86400)
            if (clean) {
                ProcessHealthTracker.heartbeat(applicationContext, "daily_worker_success")
                Result.success()
            } else {
                ProcessHealthTracker.heartbeat(applicationContext, "daily_worker_incomplete")
                retryOrFail()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Daily sync failed: ${e.message}")
            ProcessHealthTracker.heartbeat(applicationContext, "daily_worker_fail", e.javaClass.simpleName)
            retryOrFail()
        } finally {
            if (acquired) nostrClient.releaseConnection()
        }
    }

    private fun retryOrFail(): Result = if (runAttemptCount < RUN_RETRY_BUDGET) Result.retry() else Result.failure()

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

    companion object {
        private const val TAG = "DailySyncWorker"

        /** Retries of one WorkManager run, unrelated to [SyncEngine.MAX_RETRIES] (attempts per outbox row). */
        const val RUN_RETRY_BUDGET = 3

        /** Outbox rows idle (no publish attempt) for this long are abandoned. */
        const val OUTBOX_RETENTION_DAYS = 90L
    }
}
