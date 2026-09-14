package com.splitfree

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.work.Configuration
import com.splitfree.data.nostr.relay.RelayHealthMonitor
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.usecase.group.RevokeKeyUseCase
import com.splitfree.domain.usecase.group.RotateGroupKeyUseCase
import com.splitfree.domain.util.RelayDefaults
import com.splitfree.sync.event.EventProcessor
import com.splitfree.sync.worker.LiveSync
import com.splitfree.sync.worker.PowerManager
import com.splitfree.sync.worker.SyncScheduler
import com.splitfree.util.DebugLog as Log
import com.splitfree.util.ProcessHealthTracker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Application entry point. Schedules the WorkManager sync jobs, binds [LiveSync] to the process
 * lifecycle, starts relay health checks and battery-aware power management, and registers a
 * [ConnectivityManager.NetworkCallback] to trigger immediate sync when network is restored.
 */
@HiltAndroidApp
class SplitFreeApp :
    Application(),
    Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var powerManager: PowerManager

    @Inject lateinit var relayHealthMonitor: RelayHealthMonitor

    @Inject lateinit var revokeKeyUseCase: RevokeKeyUseCase

    @Inject lateinit var rotateGroupKeyUseCase: RotateGroupKeyUseCase

    @Inject lateinit var identity: IdentityContract

    @Inject lateinit var eventProcessor: EventProcessor

    @Inject lateinit var liveSync: LiveSync

    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setWorkerFactory(workerFactory)
                .build()

    private val batteryStateReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                SyncScheduler.schedulePeriodicSync(this@SplitFreeApp, powerManager.syncInterval())
            }
        }

    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                try {
                    if (!::identity.isInitialized || !identity.hasIdentity()) return
                    // This fires while invisible too, so only WorkManager is asked: it coalesces the
                    // request and runs it once connectivity is really there.
                    SyncScheduler.scheduleImmediateSync(this@SplitFreeApp)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not schedule network sync: ${e.message}")
                }
            }
        }

    override fun onCreate() {
        ProcessHealthTracker.install(this)
        super.onCreate()
        ProcessHealthTracker.heartbeat(this, "app_on_create")
        SyncScheduler.schedulePeriodicSync(this, powerManager.syncInterval())
        SyncScheduler.scheduleDailySync(this)
        liveSync.bind(ProcessLifecycleOwner.get().lifecycle)
        registerBatteryStateReceiver()
        registerNetworkCallback()
        ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) {
            try {
                revokeKeyUseCase.resumeIfNeeded()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Preserve pending identity state for a later retry, including storage failures.
                Log.e(TAG, "Could not resume pending key revocation", e)
            }
            try {
                rotateGroupKeyUseCase.resumeIfNeeded()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Could not resume interrupted key rotation", e)
            }
            eventProcessor.recoverPending()
            try {
                relayHealthMonitor.checkRelays(RelayDefaults.DEFAULT_RELAYS + RelayDefaults.FALLBACK_RELAYS)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Relay health check failed: ${e.message}")
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

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, networkCallback)
    }

    override fun onTerminate() {
        try {
            unregisterReceiver(batteryStateReceiver)
        } catch (_: Exception) {
        }
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
        super.onTerminate()
    }

    companion object {
        private const val TAG = "SplitFreeApp"
    }
}
