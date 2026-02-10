package com.splitfree.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.splitfree.data.local.EventDao
import com.splitfree.data.local.OutboxDao
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.EventValidator
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.IdentityManager
import com.splitfree.domain.model.GroupMeta
import com.splitfree.domain.usecase.CreateSnapshotUseCase
import com.splitfree.domain.usecase.SelfHealUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.serialization.json.Json
import rust.nostr.sdk.Event

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val outboxDao: OutboxDao,
    private val eventDao: EventDao,
    private val groupRepo: GroupRepository,
    private val nostrClient: NostrClient,
    private val identity: IdentityManager,
    private val encryption: GroupEncryption,
    private val signer: EventSigner,
    private val createSnapshot: CreateSnapshotUseCase,
    private val selfHeal: SelfHealUseCase,
    private val giftWrap: com.splitfree.domain.crypto.GiftWrapService,
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
            val allRelays = groupRepo.getAll()
                .flatMap { it.relays }
                .distinct()
                .ifEmpty { DEFAULT_RELAYS }
            val onlineRelays = relayHealthMonitor.getOnlineRelays(allRelays)
                .ifEmpty { allRelays } // fallback to all if none checked yet
            nostrClient.connect(identity.getKeys(), onlineRelays)
        }
        nostrClient.acquireConnection()
        return true
    }

    private suspend fun publishOutbox() {
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

            for (event in events) {
                val eventId = event.id().toHex()
                if (eventId in existingIds) continue
                if (!verifyAndStore(event, group.id, groupEntity.groupKey)) continue
                newCount++
            }

            if (newCount > 0) {
                groupRepo.updateLastSync(group.id, System.currentTimeMillis() / 1000)
                Log.i(TAG, "Pulled $newCount new events for group ${group.name}")
            }

            createSnapshot(group.id)
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun verifyAndStore(event: Event, groupId: String, groupKey: String): Boolean {
        return try {
            val inner = giftWrap.tryUnwrap(event) ?: event
            if (!signer.verify(inner)) return false

            // Reject future/stale timestamps (design doc Section 13.7)
            if (!EventValidator.isTimestampValid(inner.createdAt().asSecs().toLong())) {
                Log.w(TAG, "Rejecting event with invalid timestamp: ${inner.id().toHex()}")
                return false
            }

            // Reject events from non-members (design doc Section 13.2)
            val authorHex = inner.author().toHex()
            val group = groupRepo.getById(groupId)

            val encrypted = inner.content()
            val decrypted = try { encryption.decrypt(encrypted, groupKey) } catch (_: Exception) { null }

            var eventType = "unknown"
            var expenseUuid: String? = null
            for (tag in inner.tags().toVec()) {
                val items = tag.asVec()
                if (items.size >= 2) {
                    when (items[0]) {
                        "t" -> eventType = items[1]
                        "e" -> expenseUuid = items[1]
                    }
                }
            }

            // Allow group_meta from anyone (needed to update member list), reject others from non-members
            if (eventType != "group_meta" && group != null && authorHex !in group.members) {
                Log.w(TAG, "Rejecting event from non-member $authorHex in group $groupId")
                return false
            }

            eventDao.insert(
                EventEntity(
                    eventId = inner.id().toHex(),
                    groupId = groupId,
                    pubkey = authorHex,
                    createdAt = inner.createdAt().asSecs().toLong(),
                    kind = 30078,
                    contentEncrypted = encrypted,
                    contentDecrypted = decrypted,
                    eventType = eventType,
                    expenseUuid = expenseUuid,
                    sig = inner.signature().toHex(),
                    receivedAt = System.currentTimeMillis() / 1000,
                    originalEventJson = inner.asJson()
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

            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to process event: ${e.message}")
            false
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
