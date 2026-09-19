package com.splitfree.sync.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.splitfree.domain.usecase.group.DisplayNamePublisher
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

@HiltWorker
class DisplayNameWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val publisher: DisplayNamePublisher
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        if (publisher.drain().needsRetry) retryOrFail() else Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        retryOrFail()
    }

    private fun retryOrFail(): Result = if (runAttemptCount < 10) Result.retry() else Result.failure()
}
