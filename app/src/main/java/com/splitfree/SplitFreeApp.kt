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
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.work.Configuration
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.nostr.relay.RelayConnectionManager
import com.splitfree.data.nostr.relay.RelayHealthMonitor
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.SyncEngineContract
import com.splitfree.domain.usecase.group.RevokeKeyUseCase
import com.splitfree.domain.util.RelayDefaults
import com.splitfree.sync.worker.ForegroundSyncService
import com.splitfree.sync.worker.PowerManager
import com.splitfree.sync.worker.SyncScheduler
import com.splitfree.util.DebugLog as Log
import com.splitfree.util.ProcessHealthTracker
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Application entry point. Initializes sync scheduling, relay health checks, and battery-aware power management.
 * Registers a [ConnectivityManager.NetworkCallback] to trigger immediate sync when network is restored.
 */
@HiltAndroidApp
class SplitFreeApp :
    Application(),
    Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var powerManager: PowerManager

    @Inject lateinit var relayHealthMonitor: RelayHealthMonitor

    @Inject lateinit var revokeKeyUseCase: RevokeKeyUseCase

    @Inject lateinit var syncEngine: SyncEngineContract

    @Inject lateinit var relayConnectionManager: RelayConnectionManager

    @Inject lateinit var identity: IdentityContract

    @Inject lateinit var nostrClient: NostrClient

    private val networkSyncInProgress = AtomicBoolean(false)

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

    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!::identity.isInitialized || !identity.hasIdentity()) return
                if (!networkSyncInProgress.compareAndSet(false, true)) return
                Log.i(TAG, "Network available — triggering immediate sync")
                ProcessHealthTracker.heartbeat(this@SplitFreeApp, "network_available")
                ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) {
                    var acquiredConnection = false
                    try {
                        // Flush any pending outbox events (e.g. group_meta from offline join)
                        relayConnectionManager.ensureConnected()
                        acquiredConnection = true
                        syncEngine.flushOutbox()
                        ProcessHealthTracker.heartbeat(this@SplitFreeApp, "network_flush_ok")
                    } catch (e: Exception) {
                        Log.w(TAG, "Network-triggered outbox flush failed: ${e.message}")
                        ProcessHealthTracker.heartbeat(
                            this@SplitFreeApp,
                            "network_flush_fail",
                            e.javaClass.simpleName
                        )
                    } finally {
                        if (acquiredConnection) nostrClient.releaseConnection()
                        networkSyncInProgress.set(false)
                    }
                    // Restart ForegroundSyncService if it died while offline
                    try {
                        val serviceIntent = Intent(this@SplitFreeApp, ForegroundSyncService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(serviceIntent)
                        } else {
                            startService(serviceIntent)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not restart sync service: ${e.message}")
                    }
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        ProcessHealthTracker.install(this)
        ProcessHealthTracker.heartbeat(this, "app_on_create")
        SyncScheduler.schedulePeriodicSync(this, powerManager.syncIntervalHours())
        SyncScheduler.scheduleMidnightSync(this)
        registerBatteryStateReceiver()
        registerNetworkCallback()
        ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) {
            try {
                relayHealthMonitor.checkRelays(RelayDefaults.DEFAULT_RELAYS + RelayDefaults.FALLBACK_RELAYS)
            } catch (_: Exception) {
                // Relay health check is best-effort
            }
            try {
                revokeKeyUseCase.resumeIfNeeded()
            } catch (_: java.security.GeneralSecurityException) {
                // Covers KeyStoreException (Robolectric) and KeyPermanentlyInvalidatedException
                // (biometric change / lock screen disabled while key revocation was pending)
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
