package com.splitfree.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.IdentityManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class ForegroundSyncService : Service() {
    @Inject lateinit var nostrClient: NostrClient

    @Inject lateinit var groupRepo: GroupRepository

    @Inject lateinit var identity: IdentityManager

    @Inject lateinit var eventProcessor: EventProcessor

    @Inject lateinit var signer: EventSigner

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectionAcquired = false

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
            if (!identity.hasIdentity()) return@launch
            val groups = groupRepo.getAll()
            val allRelays = groups.flatMap { it.relays }.distinct()
            if (allRelays.isEmpty()) return@launch

            try {
                nostrClient.authSigner = { challenge, relayUrl -> signer.createAuthEvent(challenge, relayUrl) }
                nostrClient.connect(allRelays)
                nostrClient.acquireConnection()
                connectionAcquired = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect: ${e.message}")
                return@launch
            }

            val subscribedGroups = mutableSetOf<String>()
            val now = System.currentTimeMillis() / 1000
            for (group in groups) {
                nostrClient.subscribe(group.id, now - 3600)
                subscribedGroups.add(group.id)
            }

            // Observe group list for newly joined groups
            scope.launch {
                groupRepo.observeAll().collect { currentGroups ->
                    val currentNow = System.currentTimeMillis() / 1000
                    for (group in currentGroups) {
                        if (subscribedGroups.add(group.id)) {
                            nostrClient.subscribe(group.id, currentNow - 3600)
                            Log.i(TAG, "Subscribed to new group: ${group.name}")
                        }
                    }
                }
            }

            nostrClient.startListening()
            nostrClient.incomingEvents.collect { event ->
                try {
                    val result =
                        eventProcessor.process(
                            rawEvent = event,
                            nonCancellable = true,
                        )
                    if (result.stored) {
                        ExpenseNotifier.notifyIfNeeded(
                            this@ForegroundSyncService,
                            result.eventType!!,
                            result.decrypted,
                            result.authorHex!!,
                            identity.getPublicKeyHex(),
                            result.groupName ?: "Group",
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
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Background expense sync" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat
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
