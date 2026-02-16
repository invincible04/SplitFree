package com.splitfree

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import com.splitfree.data.nostr.RelayHealthMonitor
import com.splitfree.domain.usecase.RevokeKeyUseCase
import com.splitfree.sync.MidnightSyncWorker
import com.splitfree.sync.PowerManager
import com.splitfree.sync.SyncWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Duration
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class SplitFreeApp :
    Application(),
    Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var powerManager: PowerManager

    @Inject lateinit var relayHealthMonitor: RelayHealthMonitor

    @Inject lateinit var revokeKeyUseCase: RevokeKeyUseCase

    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setWorkerFactory(workerFactory)
                .build()

    private val batteryStateReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                schedulePeriodicSync()
            }
        }

    override fun onCreate() {
        super.onCreate()
        schedulePeriodicSync()
        scheduleMidnightSync()
        registerBatteryStateReceiver()
        // Check relay health on startup (design doc Section 5.5)
        ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) {
            relayHealthMonitor.checkRelays(
                com.splitfree.data.nostr.RelayConfig.DEFAULT_RELAYS + com.splitfree.data.nostr.RelayConfig.FALLBACK_RELAYS
            )
            // Resume incomplete key revocation if app was killed mid-revocation (V8 fix)
            revokeKeyUseCase.resumeIfNeeded()
        }
    }

    private fun schedulePeriodicSync() {
        val syncRequest =
            PeriodicWorkRequestBuilder<SyncWorker>(
                powerManager.syncIntervalHours(),
                TimeUnit.HOURS,
            ).setConstraints(
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            ).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
                .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "splitfree_periodic_sync",
            ExistingPeriodicWorkPolicy.UPDATE,
            syncRequest,
        )
    }

    private fun scheduleMidnightSync() {
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
                    Constraints
                        .Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                ).build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            MidnightSyncWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private fun registerBatteryStateReceiver() {
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_BATTERY_LOW)
                addAction(Intent.ACTION_BATTERY_OKAY)
            }
        registerReceiver(batteryStateReceiver, filter)
    }

    override fun onTerminate() {
        try {
            unregisterReceiver(batteryStateReceiver)
        } catch (_: Exception) {
        }
        super.onTerminate()
    }
}
