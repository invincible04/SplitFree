package com.splitfree

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.*
import com.splitfree.sync.PowerManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Duration
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class SplitFreeApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var powerManager: PowerManager
    @Inject lateinit var relayHealthMonitor: com.splitfree.data.nostr.RelayHealthMonitor
    @Inject lateinit var revokeKeyUseCase: com.splitfree.domain.usecase.RevokeKeyUseCase

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private val batteryStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            schedulePeriodicSync()
        }
    }

    override fun onCreate() {
        super.onCreate()
        schedulePeriodicSync()
        scheduleMidnightSync()
        registerBatteryStateReceiver()
        // Check relay health on startup (design doc Section 5.5)
        CoroutineScope(Dispatchers.IO).launch {
            relayHealthMonitor.checkRelays(com.splitfree.sync.SyncWorker.DEFAULT_RELAYS)
            // Resume incomplete key revocation if app was killed mid-revocation (V8 fix)
            revokeKeyUseCase.resumeIfNeeded()
        }
    }

    private fun schedulePeriodicSync() {
        val syncRequest = PeriodicWorkRequestBuilder<com.splitfree.sync.SyncWorker>(
            powerManager.syncIntervalHours(), TimeUnit.HOURS
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "splitfree_periodic_sync",
            ExistingPeriodicWorkPolicy.UPDATE,
            syncRequest
        )
    }

    private fun scheduleMidnightSync() {
        val now = Calendar.getInstance()
        val midnight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            if (before(now)) add(Calendar.DAY_OF_MONTH, 1)
        }
        val delay = midnight.timeInMillis - now.timeInMillis
        val request = OneTimeWorkRequestBuilder<com.splitfree.sync.MidnightSyncWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            com.splitfree.sync.MidnightSyncWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    private fun registerBatteryStateReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
        }
        registerReceiver(batteryStateReceiver, filter)
    }
}
