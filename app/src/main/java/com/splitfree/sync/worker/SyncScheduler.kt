package com.splitfree.sync.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Schedules periodic and midnight sync jobs via WorkManager.
 */
object SyncScheduler {
    /** A bounded, network-constrained fallback for boot and connectivity restoration. */
    fun scheduleImmediateSync(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "splitfree_immediate_sync",
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun schedulePeriodicSync(context: Context, intervalHours: Long) {
        val request =
            PeriodicWorkRequestBuilder<SyncWorker>(intervalHours, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
                .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "splitfree_periodic_sync",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun scheduleMidnightSync(context: Context) {
        val now = Calendar.getInstance()
        val midnight =
            Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                if (before(now)) add(Calendar.DAY_OF_MONTH, 1)
            }
        val delay = midnight.timeInMillis - now.timeInMillis
        val request =
            OneTimeWorkRequestBuilder<MidnightSyncWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            MidnightSyncWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}
