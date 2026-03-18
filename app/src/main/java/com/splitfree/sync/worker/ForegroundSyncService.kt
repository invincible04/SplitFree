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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
    private var connectionAcquired = false

    @Volatile private var connectedRelaySet = emptySet<String>()

    @Volatile private var syncRunning = false

    private val syncStartInProgress = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        ProcessHealthTracker.heartbeat(this, "fg_service_create")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        triggerRealtimeSyncIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-trigger sync if previous attempt failed (e.g. was offline, now network callback restarted us)
        triggerRealtimeSyncIfNeeded()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        ProcessHealthTracker.heartbeat(this, "fg_service_destroy")
        scope.cancel()
        syncRunning = false
        syncStartInProgress.set(false)
        if (connectionAcquired) {
            nostrClient.releaseConnection()
            connectionAcquired = false
        }
        super.onDestroy()
    }

    private fun triggerRealtimeSyncIfNeeded() {
        if (syncRunning) return
        if (!syncStartInProgress.compareAndSet(false, true)) return
        startRealtimeSync()
    }

    private fun startRealtimeSync() {
        scope.launch {
            try {
                if (!identity.hasIdentity()) {
                    Log.w(TAG, "No identity — skipping sync")
                    ProcessHealthTracker.heartbeat(this@ForegroundSyncService, "fg_sync_skip_no_identity")
                    return@launch
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
                        relayConnectionManager.ensureConnected()
                        connectionAcquired = true
                        connectedRelaySet = relayConnectionManager.resolvePrimaryRelays().toSet()
                        connected = true
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "Connect attempt $attempt failed: ${e.message}")
                        delay(minOf(30_000L * attempt, 120_000L))
                    }
                }
                if (!connected) {
                    Log.w(TAG, "All connect attempts failed — waiting for network callback to retry")
                    ProcessHealthTracker.heartbeat(this@ForegroundSyncService, "fg_connect_failed_all")
                    return@launch
                }
                ProcessHealthTracker.heartbeat(this@ForegroundSyncService, "fg_connected")

                syncRunning = true

                // Flush any pending outbox events (e.g. group_meta from offline join)
                try {
                    syncEngine.flushOutbox()
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
                scope.launch {
                    groupRepo.observeAll().collect { currentGroups ->
                        val currentNow = System.currentTimeMillis() / 1000

                        // Check if relay set has changed (compare group relays only, excluding fallbacks)
                        val currentRelaySet = currentGroups.flatMap { it.relays }.toSet()
                        val previousRelaySet =
                            connectedRelaySet - RelayDefaults.FALLBACK_RELAYS.toSet()
                        if (currentRelaySet != previousRelaySet) {
                            Log.i(TAG, "Relay set changed, reconnecting...")
                            try {
                                relayConnectionManager.ensureConnected(forceReconnect = true)
                                connectedRelaySet = relayConnectionManager.resolvePrimaryRelays().toSet()
                                // Re-subscribe all groups on new connections
                                for (group in currentGroups) {
                                    nostrClient.subscribe(group.id, currentNow - 3600, myPubkey)
                                }
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
                                } catch (e: Exception) {
                                    Log.w(TAG, "Outbox flush on new group failed: ${e.message}")
                                }
                            }
                        }
                    }
                }

                // Monitor connection state — reconnect if all relays drop
                scope.launch {
                    nostrClient.connectionState.collect { isConnected ->
                        if (!isConnected && connectionAcquired) {
                            Log.w(TAG, "Lost all relay connections — attempting reconnect")
                            delay(5_000)
                            try {
                                relayConnectionManager.ensureConnected(forceReconnect = true)
                                connectedRelaySet = relayConnectionManager.resolvePrimaryRelays().toSet()
                                val currentNow = System.currentTimeMillis() / 1000
                                for (group in groupRepo.getAll()) {
                                    nostrClient.subscribe(group.id, currentNow - 3600, myPubkey)
                                }
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
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to process event: ${e.message}")
                    }
                }
            } finally {
                syncRunning = false
                syncStartInProgress.set(false)
            }
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
