package com.splitfree.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.splitfree.data.local.EventDao
import com.splitfree.data.local.OutboxDao
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.IdentityManager
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.usecase.CreateSnapshotUseCase
import com.splitfree.domain.usecase.SelfHealUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val outboxDao: OutboxDao,
    private val eventDao: EventDao,
    private val groupRepo: GroupRepository,
    private val nostrClient: NostrClient,
    private val identity: IdentityManager,
    private val createSnapshot: CreateSnapshotUseCase,
    private val selfHeal: SelfHealUseCase,
    private val eventProcessor: EventProcessor,
    private val relayHealthMonitor: com.splitfree.data.nostr.RelayHealthMonitor
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        var acquiredConnection = false
        return try {
            if (!identity.hasIdentity()) return Result.success()
            acquiredConnection = ensureConnected()
            publishOutbox()
            pullFromRelays()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed: ${e.message}")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        } finally {
            if (acquiredConnection) nostrClient.releaseConnection()
        }
    }

    private suspend fun ensureConnected(): Boolean {
        if (!nostrClient.isConnected) {
            nostrClient.authSigner = { challenge, relayUrl -> createAuthEvent(challenge, relayUrl) }
            val allRelays = groupRepo.getAll()
                .flatMap { it.relays }
                .distinct()
                .ifEmpty { DEFAULT_RELAYS }
            val onlineRelays = relayHealthMonitor.getOnlineRelays(allRelays)
                .ifEmpty { allRelays }
            nostrClient.connect(onlineRelays)
        }
        nostrClient.acquireConnection()
        return true
    }

    private fun createAuthEvent(challenge: String, relayUrl: String): NostrEvent {
        val privKey = identity.getPrivateKeyBytes()
        try {
            return NostrEvent(
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

    private suspend fun publishOutbox() {
        val sevenDaysAgo = System.currentTimeMillis() / 1000 - 7 * 86400
        outboxDao.deleteOlderThan(sevenDaysAgo)

        val pending = outboxDao.getAll()
        for (event in pending) {
            val success = nostrClient.publishJson(event.eventJson)
            if (success) {
                outboxDao.delete(event.eventId)
            } else {
                if (event.retryCount >= MAX_RETRIES) {
                    Log.w(TAG, "Dropping event ${event.eventId} after $MAX_RETRIES retries")
                    outboxDao.delete(event.eventId)
                } else {
                    outboxDao.incrementRetry(event.eventId, System.currentTimeMillis() / 1000)
                }
            }
        }
    }

    private suspend fun pullFromRelays() {
        val groups = groupRepo.getAll()
        for (group in groups) {
            val groupEntity = groupRepo.getGroupEntity(group.id) ?: continue
            val since = groupEntity.lastSyncTimestamp - 3600

            val events = nostrClient.fetchEvents(group.id, since)
            val existingIds = eventDao.getEventIds(group.id).toSet()
            var newCount = 0

            val groupKey = groupRepo.getGroupKey(group.id) ?: continue
            for (event in events) {
                if (event.id in existingIds) continue
                val result = eventProcessor.process(
                    rawEvent = event,
                    knownGroupId = group.id,
                    knownGroupKey = groupKey
                )
                if (result.stored) {
                    ExpenseNotifier.notifyIfNeeded(
                        applicationContext, result.eventType!!, result.decrypted,
                        result.authorHex!!, identity.getPublicKeyHex(), result.groupName ?: "Group"
                    )
                    newCount++
                }
            }

            if (newCount > 0) {
                groupRepo.updateLastSync(group.id, System.currentTimeMillis() / 1000)
                Log.i(TAG, "Pulled $newCount new events for group ${group.name}")
            }

            createSnapshot(group.id)
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val MAX_RETRIES = 5
        val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.nostr.band",
            "wss://relay.snort.social",
            "wss://nostr.wine"
        )
    }
}
