package com.splitfree.sync.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.splitfree.data.identity.IdentityManager
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.nostr.relay.RelayConnectionManager
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.util.RelayDefaults
import com.splitfree.sync.event.EventProcessor
import com.splitfree.sync.event.ExpenseNotifier
import com.splitfree.util.DebugLog as Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    @Inject lateinit var groupRepo: GroupRepository

    @Inject lateinit var identity: IdentityManager

    @Inject lateinit var eventProcessor: EventProcessor

    @Inject lateinit var relayConnectionManager: RelayConnectionManager

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectionAcquired = false

    @Volatile private var connectedRelaySet = emptySet<String>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        startRealtimeSync()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        if (connectionAcquired) {
            nostrClient.releaseConnection()
            connectionAcquired = false
        }
        super.onDestroy()
    }

    private fun startRealtimeSync() {
        scope.launch {
            if (!identity.hasIdentity()) {
                Log.w(TAG, "No identity — skipping sync")
                return@launch
            }

            // Wait until at least one group exists (handles fresh install)
            val groups =
                groupRepo.getAll().ifEmpty {
                    Log.i(TAG, "No groups yet — waiting for first group")
                    groupRepo.observeAll().first { it.isNotEmpty() }
                }

            try {
                relayConnectionManager.ensureConnected()
                connectionAcquired = true
                connectedRelaySet = relayConnectionManager.resolvePrimaryRelays().toSet()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect: ${e.message}")
                return@launch
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
                            "Processed: ${result.eventType} from ${result.authorHex?.take(8)} in ${result.groupName}"
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
