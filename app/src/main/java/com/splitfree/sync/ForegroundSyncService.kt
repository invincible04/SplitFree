package com.splitfree.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.splitfree.data.local.EventDao
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.EventValidator
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.IdentityManager
import com.splitfree.domain.model.GroupMeta
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import javax.inject.Inject

@AndroidEntryPoint
class ForegroundSyncService : Service() {

    @Inject lateinit var nostrClient: NostrClient
    @Inject lateinit var groupRepo: GroupRepository
    @Inject lateinit var eventDao: EventDao
    @Inject lateinit var identity: IdentityManager
    @Inject lateinit var encryption: GroupEncryption
    @Inject lateinit var signer: EventSigner
    @Inject lateinit var giftWrap: com.splitfree.domain.crypto.GiftWrapService

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectionAcquired = false
    private val json = Json { ignoreUnknownKeys = true }

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
                nostrClient.authSigner = { challenge, relayUrl -> createAuthEvent(challenge, relayUrl) }
                nostrClient.connect(allRelays)
                nostrClient.acquireConnection()
                connectionAcquired = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect: ${e.message}")
                return@launch
            }

            val now = System.currentTimeMillis() / 1000
            for (group in groups) {
                nostrClient.subscribe(group.id, now - 3600)
            }

            nostrClient.startListening()
            nostrClient.incomingEvents.collect { event ->
                processIncomingEvent(event)
            }
        }
    }

    private suspend fun processIncomingEvent(event: com.splitfree.domain.crypto.NostrEvent) {
        try {
            val unwrapResult = giftWrap.tryUnwrap(event)
            val inner = unwrapResult?.first ?: event
            if (!signer.verify(inner)) return

            if (!EventValidator.isTimestampValid(inner.createdAt)) {
                Log.w(TAG, "Rejecting event with invalid timestamp: ${inner.id}")
                return
            }

            val eventId = inner.id
            if (eventDao.getEvent(eventId) != null) return

            var groupId: String? = null
            var eventType = "unknown"
            var expenseUuid: String? = null
            for (tag in inner.tags) {
                if (tag.size >= 2) {
                    when (tag[0]) {
                        "g" -> groupId = tag[1]
                        "t" -> eventType = tag[1]
                        "e" -> expenseUuid = tag[1]
                    }
                }
            }
            groupId ?: return

            val authorHex = inner.pubkey
            val group = groupRepo.getById(groupId)
            if (eventType != "group_meta" && group != null && authorHex !in group.members) {
                Log.w(TAG, "Rejecting event from non-member $authorHex in group $groupId")
                return
            }

            val groupKey = groupRepo.getGroupKey(groupId) ?: return
            val encrypted = inner.content
            val decrypted = try { encryption.decrypt(encrypted, groupKey) } catch (_: Exception) { null }

            eventDao.insert(
                EventEntity(
                    eventId = eventId,
                    groupId = groupId,
                    pubkey = authorHex,
                    createdAt = inner.createdAt,
                    kind = 30078,
                    contentEncrypted = encrypted,
                    contentDecrypted = decrypted,
                    eventType = eventType,
                    expenseUuid = expenseUuid,
                    sig = inner.sig,
                    receivedAt = System.currentTimeMillis() / 1000,
                    originalEventJson = inner.toJson()
                )
            )

            if (eventType == "group_meta" && decrypted != null) {
                try {
                    val meta = json.decodeFromString<GroupMeta>(decrypted)
                    if (meta.members.isNotEmpty()) {
                        groupRepo.updateFromMeta(groupId, meta.name, meta.members, meta.relays)
                    }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to process event: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Sync Service", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Background expense sync" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SplitFree")
            .setContentText("Syncing expenses in background")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .build()

    private fun createAuthEvent(challenge: String, relayUrl: String): com.splitfree.domain.crypto.NostrEvent {
        val privKey = identity.getPrivateKeyBytes()
        try {
            return com.splitfree.domain.crypto.NostrEvent(
                pubkey = identity.getPublicKeyHex(),
                createdAt = System.currentTimeMillis() / 1000,
                kind = 22242,
                tags = listOf(listOf("challenge", challenge), listOf("relay", relayUrl)),
                content = ""
            ).sign(privKey)
        } finally {
            privKey.fill(0)
        }
    }

    companion object {
        private const val TAG = "ForegroundSyncService"
        private const val CHANNEL_ID = "splitfree_sync"
        private const val NOTIFICATION_ID = 1
    }
}
