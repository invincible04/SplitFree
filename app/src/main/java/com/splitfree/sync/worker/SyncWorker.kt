package com.splitfree.sync.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.nostr.relay.RelayConnectionManager
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.util.DebugLog as Log
import com.splitfree.util.ProcessHealthTracker
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * Background catch-up: flush the outbox, then pull each group incrementally from its cursor.
 *
 * Runs periodically on the [PowerManager.syncInterval] schedule and as the one-time boot/network
 * job from [SyncScheduler.scheduleImmediateSync]. Full reconciliation, self-heal and snapshots
 * belong to [DailySyncWorker].
 *
 * The run succeeds only when every attempted outbox row published and every fetch was complete;
 * otherwise, or on an exception, it retries up to [RUN_RETRY_BUDGET] times and then fails.
 * Cancellation is rethrown, never reported as a result.
 */
@HiltWorker
class SyncWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val groupRepo: GroupRepository,
    private val nostrClient: NostrClient,
    private val identity: IdentityContract,
    private val relayConnectionManager: RelayConnectionManager,
    private val syncEngine: SyncEngine
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        var acquired = false
        ProcessHealthTracker.heartbeat(applicationContext, "sync_worker_start")
        return try {
            if (!identity.hasIdentity()) return Result.success()
            relayConnectionManager.ensureConnected()
            acquired = true
            var clean = true
            val flush = syncEngine.flushOutbox()
            if (flush.failed > 0) {
                Log.w(TAG, "${flush.failed} outbox row(s) failed to publish")
                clean = false
            }
            for (group in groupRepo.getAll()) {
                val entity = groupRepo.getGroupEntity(group.id) ?: continue
                val groupKey = groupRepo.getGroupKey(group.id) ?: continue
                val since = if (entity.lastSyncTimestamp > 0) entity.lastSyncTimestamp - 3600 else 0L
                val pull = syncEngine.pullEvents(group.id, since, groupKey, notifyContext = applicationContext)
                if (pull.stored > 0) Log.i(TAG, "Pulled ${pull.stored} new events for group ${group.name}")
                if (!pull.complete) {
                    Log.w(TAG, "Incomplete pull for group ${group.name}; missing relay coverage retained")
                    clean = false
                }
            }
            if (clean) {
                ProcessHealthTracker.heartbeat(applicationContext, "sync_worker_success")
                Result.success()
            } else {
                ProcessHealthTracker.heartbeat(applicationContext, "sync_worker_incomplete")
                retryOrFail()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sync failed: ${e.message}")
            ProcessHealthTracker.heartbeat(applicationContext, "sync_worker_fail", e.javaClass.simpleName)
            retryOrFail()
        } finally {
            if (acquired) nostrClient.releaseConnection()
        }
    }

    private fun retryOrFail(): Result = if (runAttemptCount < RUN_RETRY_BUDGET) Result.retry() else Result.failure()

    companion object {
        private const val TAG = "SyncWorker"

        /** Retries of one WorkManager run, unrelated to [SyncEngine.MAX_RETRIES] (attempts per outbox row). */
        const val RUN_RETRY_BUDGET = 3
    }
}
