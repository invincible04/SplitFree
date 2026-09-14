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
import java.time.ZonedDateTime

/**
 * Schedules the WorkManager sync jobs: the immediate/periodic [SyncWorker], the outbox drain
 * [OutboxWorker] and the daily [DailySyncWorker].
 */
object SyncScheduler {
    const val OUTBOX_DRAIN_WORK = "splitfree_outbox_drain"
    const val DAILY_SYNC_WORK = "splitfree_daily_sync"

    /**
     * A bounded, network-constrained one-time [SyncWorker]: requested on boot, when connectivity
     * returns, and by [LiveSync] when its visible session fails. `KEEP` collapses bursts into one run.
     */
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

    /**
     * Durable delivery for committed outbox rows: one network-constrained [OutboxWorker] that
     * retries while attempted rows keep failing. Requested after every commit.
     */
    fun scheduleOutboxDrain(context: Context) {
        // Not expedited: on API 26-30 expedited work needs a foreground notification, the thing
        // this design removes.
        val request = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
            .build()
        // KEEP would drop a request that arrives while a run is already past its flush, leaving the new
        // row to the periodic sync; APPEND_OR_REPLACE queues a run behind it and replaces a chain that
        // has already failed instead of appending to a dead one.
        WorkManager.getInstance(context).enqueueUniqueWork(
            OUTBOX_DRAIN_WORK,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }

    /**
     * Periodic incremental catch-up; [interval] comes from [PowerManager.syncInterval]. `UPDATE`
     * rewrites the pending request in place (keeping its original enqueue time) when the battery
     * tier changes the interval, instead of cancelling and restarting the period from now.
     */
    fun schedulePeriodicSync(context: Context, interval: Duration) {
        val request =
            PeriodicWorkRequestBuilder<SyncWorker>(interval)
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

    /**
     * Daily full reconciliation: first run at the next local midnight, then every 24 hours as far as
     * WorkManager honours the period. `KEEP` leaves an already scheduled chain in place.
     */
    fun scheduleDailySync(context: Context) {
        val request =
            PeriodicWorkRequestBuilder<DailySyncWorker>(Duration.ofHours(24))
                .setInitialDelay(untilNextLocalMidnight(ZonedDateTime.now()))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DAILY_SYNC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /** Wall-clock distance to the next 00:00 in [now]'s zone, so a DST night yields 23h or 25h, not 24h. */
    internal fun untilNextLocalMidnight(now: ZonedDateTime): Duration =
        Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(now.zone))
}
