package com.splitfree

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.work.Configuration
import com.splitfree.data.nostr.relay.RelayHealthMonitor
import com.splitfree.domain.usecase.group.RevokeKeyUseCase
import com.splitfree.domain.util.RelayDefaults
import com.splitfree.sync.worker.PowerManager
import com.splitfree.sync.worker.SyncScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Application entry point. Initializes sync scheduling, relay health checks, and battery-aware power management.
 */
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
            override fun onReceive(context: Context?, intent: Intent?) {
                SyncScheduler.schedulePeriodicSync(this@SplitFreeApp, powerManager.syncIntervalHours())
            }
        }

    override fun onCreate() {
        super.onCreate()
        SyncScheduler.schedulePeriodicSync(this, powerManager.syncIntervalHours())
        SyncScheduler.scheduleMidnightSync(this)
        registerBatteryStateReceiver()
        ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) {
            try {
                relayHealthMonitor.checkRelays(RelayDefaults.DEFAULT_RELAYS + RelayDefaults.FALLBACK_RELAYS)
                revokeKeyUseCase.resumeIfNeeded()
            } catch (_: Exception) {
                // AndroidKeyStore unavailable in test environments (Robolectric)
            }
        }
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
