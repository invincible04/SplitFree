package com.splitfree.sync.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.nostr.relay.RelayConnectionManager
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.IdentityManager
import com.splitfree.domain.usecase.expense.CreateSnapshotUseCase
import com.splitfree.domain.usecase.sync.SelfHealUseCase
import com.splitfree.util.DebugLog as Log
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Periodic background sync worker.
 *
 * Runs on a battery-adaptive schedule (1–24h depending on [PowerMode]).
 * For each group: flushes outbox → pulls new events → self-heals → creates snapshots.
 */
@HiltWorker
class SyncWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val groupRepo: GroupRepository,
    private val nostrClient: NostrClient,
    private val identity: IdentityManager,
    private val createSnapshot: CreateSnapshotUseCase,
    private val selfHeal: SelfHealUseCase,
    private val relayConnectionManager: RelayConnectionManager,
    private val syncEngine: SyncEngine
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        var acquiredConnection = false
        return try {
            if (!identity.hasIdentity()) return Result.success()
            relayConnectionManager.ensureConnected()
            acquiredConnection = true
            syncEngine.flushOutbox()
            for (group in groupRepo.getAll()) {
                val entity = groupRepo.getGroupEntity(group.id) ?: continue
                val groupKey = groupRepo.getGroupKey(group.id) ?: continue
                val since = if (entity.lastSyncTimestamp > 0) entity.lastSyncTimestamp - 3600 else 0L
                val count = syncEngine.pullEvents(group.id, since, groupKey, notifyContext = applicationContext)
                if (count > 0) Log.i(TAG, "Pulled $count new events for group ${group.name}")
                selfHeal(group.id)
                createSnapshot(group.id)
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed: ${e.message}")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        } finally {
            if (acquiredConnection) nostrClient.releaseConnection()
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
    }
}
