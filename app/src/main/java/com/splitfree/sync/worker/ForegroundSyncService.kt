package com.splitfree.sync.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.nostr.relay.RelayConnectionManager
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.util.RelayDefaults
import com.splitfree.sync.event.EventProcessor
import com.splitfree.sync.event.ExpenseNotifier
import com.splitfree.util.DebugLog as Log
import com.splitfree.util.ProcessHealthTracker
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service for real-time event sync via persistent WebSocket connections.
 *
 * Started when the user has an active identity. Subscribes to all groups,
 * processes incoming events in real-time, and shows notifications for new
 * expenses and settlements from other members. Automatically reconnects
 * when the relay set changes (e.g. after joining a group with different relays).
 */
@AndroidEntryPoint
class ForegroundSyncService : Service() {
    @Inject lateinit var nostrClient: NostrClient

    @Inject lateinit var groupRepo: GroupRepositoryContract

    @Inject lateinit var identity: IdentityContract

    @Inject lateinit var eventProcessor: EventProcessor

    @Inject lateinit var relayConnectionManager: RelayConnectionManager

    @Inject lateinit var syncEngine: SyncEngine

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val stopping = AtomicBoolean(false)

    @Volatile private var connectedRelaySet = emptySet<String>()

    @Volatile private var syncRunning = false

    private val syncStartInProgress = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        ProcessHealthTracker.heartbeat(this, "fg_service_create")
        try {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: IllegalStateException) {
            // Android may reject promotion after the caller successfully requested a start.
            Log.w(TAG, "Foreground sync unavailable: ${e.message}")
            stopSyncService()
            return
        } catch (e: SecurityException) {
            Log.w(TAG, "Foreground sync permission denied: ${e.message}")
            stopSyncService()
            return
        }
        triggerRealtimeSyncIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        triggerRealtimeSyncIfNeeded()
        // Do not restart a dataSync service behind the user's back after its time budget expires.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        ProcessHealthTracker.heartbeat(this, "fg_service_destroy")
        stopping.set(true)
        scope.cancel()
        // The session owns its connection; its finally releases it after children stop.
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // API 35: the OS kills the process unless we stop within a few seconds.
        // Stop unconditionally, not stopSelf(startId), which can ignore a stale start ID.
        stopSyncService()
        scheduleFallbackSync()
        ProcessHealthTracker.heartbeat(this, "fg_service_timeout", "type=$fgsType")
    }

    private fun scheduleFallbackSync() {
        try {
            SyncScheduler.scheduleImmediateSync(this)
        } catch (e: Exception) {
            // A WorkManager failure must not prevent a timed-out/failed service from stopping.
            Log.w(TAG, "Could not schedule fallback sync: ${e.message}")
        }
    }

    private fun stopSyncService() {
        stopping.set(true)
        scope.cancel()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } finally {
            stopSelf()
        }
    }

    private fun triggerRealtimeSyncIfNeeded() {
        if (stopping.get() || syncRunning) return
        if (!syncStartInProgress.compareAndSet(false, true)) return
        startRealtimeSync()
    }

    private fun startRealtimeSync() {
        scope.launch {
            var connectionAcquired = false
            try {
                coroutineScope {
                    if (!identity.hasIdentity()) {
                        Log.w(TAG, "No identity — skipping sync")
                        ProcessHealthTracker.heartbeat(this@ForegroundSyncService, "fg_sync_skip_no_identity")
                        withContext(Dispatchers.Main.immediate) { stopSyncService() }
                        return@coroutineScope
                    }

                    // Wait until at least one group exists (handles fresh install)
                    val groups =
                        groupRepo.getAll().ifEmpty {
                            Log.i(TAG, "No groups yet — waiting for first group")
                            groupRepo.observeAll().first { it.isNotEmpty() }
                        }

                    var connected = false
                    for (attempt in 1..5) {
                        try {
                            val primaryRelays = relayConnectionManager.resolvePrimaryRelays().toSet()
                            relayConnectionManager.ensureConnected()
                            connectionAcquired = true
                            connectedRelaySet = primaryRelays
                            connected = true
                            break
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Connect attempt $attempt failed: ${e.message}")
                            delay(minOf(30_000L * attempt, 120_000L))
                        }
                    }
                    if (!connected) {
                        Log.w(TAG, "All connect attempts failed; scheduled sync remains available")
                        ProcessHealthTracker.heartbeat(this@ForegroundSyncService, "fg_connect_failed_all")
                        withContext(Dispatchers.Main.immediate) { stopSyncService() }
                        return@coroutineScope
                    }
                    ProcessHealthTracker.heartbeat(this@ForegroundSyncService, "fg_connected")

                    syncRunning = true

                    // Flush any pending outbox events (e.g. group_meta from offline join)
                    try {
                        syncEngine.flushOutbox()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Initial outbox flush failed: ${e.message}")
                    }

                    val subscribedGroups = mutableSetOf<String>()
                    val now = System.currentTimeMillis() / 1000
                    val myPubkey = identity.getPublicKeyHex()
                    for (group in groups) {
                        nostrClient.subscribe(group.id, now - 3600, myPubkey)
                        subscribedGroups.add(group.id)
                    }

                    // Observe group list for newly joined groups AND relay changes
                    launch {
                        groupRepo.observeAll().collect { currentGroups ->
                            val currentNow = System.currentTimeMillis() / 1000

                            // Check if relay set has changed (compare group relays only, excluding fallbacks)
                            val currentRelaySet = currentGroups.flatMap { it.relays }.toSet()
                            val primaryRelays = currentRelaySet.ifEmpty { RelayDefaults.DEFAULT_RELAYS.toSet() }
                            if (primaryRelays != connectedRelaySet) {
                                Log.i(TAG, "Relay set changed, reconnecting...")
                                try {
                                    reconnectRetainingSession()
                                    // Re-subscribe all groups on new connections
                                    for (group in currentGroups) {
                                        nostrClient.subscribe(group.id, currentNow - 3600, myPubkey)
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Log.w(TAG, "Reconnect failed: ${e.message}")
                                }
                            }

                            for (group in currentGroups) {
                                if (subscribedGroups.add(group.id)) {
                                    nostrClient.subscribe(group.id, currentNow - 3600, myPubkey)
                                    Log.i(TAG, "Subscribed to new group: ${group.name}")
                                    // Flush outbox so pending events (e.g. join announcement) get published
                                    try {
                                        syncEngine.flushOutbox()
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Outbox flush on new group failed: ${e.message}")
                                    }
                                }
                            }
                        }
                    }

                    // Monitor connection state — reconnect if all relays drop
                    launch {
                        nostrClient.connectionState.collect { isConnected ->
                            if (!isConnected && connectionAcquired) {
                                Log.w(TAG, "Lost all relay connections — attempting reconnect")
                                delay(5_000)
                                try {
                                    reconnectRetainingSession()
                                    val currentNow = System.currentTimeMillis() / 1000
                                    for (group in groupRepo.getAll()) {
                                        nostrClient.subscribe(group.id, currentNow - 3600, myPubkey)
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Log.e(TAG, "Reconnect failed: ${e.message}")
                                }
                            }
                        }
                    }

                    nostrClient.startListening()
                    Log.i(TAG, "Listening for incoming events...")
                    nostrClient.incomingEvents.collect { event ->
                        Log.d(TAG, "Received event ${event.id.take(8)} kind=${event.kind} from=${event.pubkey.take(8)}")
                        try {
                            val result =
                                eventProcessor.process(
                                    rawEvent = event,
                                    nonCancellable = true
                                )
                            if (result.stored) {
                                Log.i(
                                    TAG,
                                    "Processed: ${result.eventType} from ${result.authorHex?.take(
                                        8
                                    )} in ${result.groupName}"
                                )
                                ExpenseNotifier.notifyIfNeeded(
                                    this@ForegroundSyncService,
                                    result.eventType!!,
                                    result.decrypted,
                                    result.authorHex!!,
                                    identity.getPublicKeyHex(),
                                    result.groupName ?: "Group"
                                )
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to process event: ${e.message}")
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Real-time sync session failed; scheduled sync remains available", e)
                scheduleFallbackSync()
                ProcessHealthTracker.heartbeat(this@ForegroundSyncService, "fg_sync_failed", e.javaClass.simpleName)
                withContext(Dispatchers.Main.immediate) { stopSyncService() }
            } finally {
                if (connectionAcquired) {
                    try {
                        nostrClient.releaseConnection()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to release sync connection: ${e.message}")
                    }
                }
                syncRunning = false
                syncStartInProgress.set(false)
            }
        }
    }

    private suspend fun reconnectRetainingSession() {
        val primaryRelays = relayConnectionManager.resolvePrimaryRelays().toSet()
        relayConnectionManager.ensureConnected(forceReconnect = true)
        try {
            connectedRelaySet = primaryRelays
        } finally {
            // ensureConnected acquires a temporary reference even on reconnect.
            nostrClient.releaseConnection()
        }
    }

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "Sync Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Background expense sync" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification = NotificationCompat
        .Builder(this, CHANNEL_ID)
        .setContentTitle("SplitFree")
        .setContentText("Syncing expenses in background")
        .setSmallIcon(android.R.drawable.ic_popup_sync)
        .setOngoing(true)
        .build()

    companion object {
        private const val TAG = "ForegroundSyncService"
        private const val CHANNEL_ID = "splitfree_sync"
        private const val NOTIFICATION_ID = 1
    }
}
