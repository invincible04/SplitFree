package com.splitfree.sync.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.nostr.relay.RelayConnectionManager
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.util.DebugLog as Log
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * Durable outbox drain, enqueued by [SyncScheduler.scheduleOutboxDrain] after every committed save.
 *
 * Succeeds without connecting when there is no identity or no outbox row is due, so the runs
 * appended behind an already-draining chain cost no connection (they still read the outbox).
 * Otherwise it connects, flushes once and, while any attempted row failed to publish, retries with
 * exponential back-off up to [RUN_RETRY_BUDGET] times before the chain fails; rows left behind are
 * reached by the periodic [SyncWorker] and by the chain the next save enqueues.
 */
@HiltWorker
class OutboxWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val identity: IdentityContract,
    private val nostrClient: NostrClient,
    private val relayConnectionManager: RelayConnectionManager,
    private val syncEngine: SyncEngine
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        var acquired = false
        return try {
            if (!identity.hasIdentity()) return Result.success()
            if (!syncEngine.hasDueOutbox()) return Result.success()
            relayConnectionManager.ensureConnected()
            acquired = true
            val flush = syncEngine.flushOutbox()
            if (flush.failed == 0) {
                Result.success()
            } else {
                Log.w(TAG, "Outbox drain left ${flush.failed} row(s) unpublished (attempt $runAttemptCount)")
                retryOrFail()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Outbox drain failed: ${e.message}")
            retryOrFail()
        } finally {
            if (acquired) nostrClient.releaseConnection()
        }
    }

    private fun retryOrFail(): Result = if (runAttemptCount < RUN_RETRY_BUDGET) Result.retry() else Result.failure()

    companion object {
        private const val TAG = "OutboxWorker"

        /** Retries of one request in the drain chain; an exhausted request fails the chain. */
        const val RUN_RETRY_BUDGET = 10
    }
}
