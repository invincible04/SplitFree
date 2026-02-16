package com.splitfree.sync

import android.content.Context
import com.splitfree.util.DebugLog as Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.splitfree.data.local.EventDao
import com.splitfree.data.local.OutboxDao
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.nostr.RelayConfig
import com.splitfree.data.nostr.RelayConnectionManager
import com.splitfree.data.nostr.RelayHealthMonitor
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GiftWrapService
import com.splitfree.domain.crypto.IdentityManager
import com.splitfree.domain.usecase.CreateSnapshotUseCase
import com.splitfree.domain.usecase.SelfHealUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SyncWorker
    @AssistedInject
    constructor(
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
        private val relayConnectionManager: RelayConnectionManager,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            var acquiredConnection = false
            return try {
                if (!identity.hasIdentity()) return Result.success()
                relayConnectionManager.ensureConnected()
                acquiredConnection = true
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

        private suspend fun publishOutbox() {
            // Only delete events older than 7 days that have been successfully published.
            // Never delete by age alone — unsent events must survive until self-heal picks them up.
            val pending = outboxDao.getAll()
            if (pending.isNotEmpty()) Log.i(TAG, "Publishing ${pending.size} outbox events")
            for (event in pending) {
                val success = nostrClient.publishJson(event.eventJson)
                if (success) {
                    outboxDao.delete(event.eventId)
                } else {
                    outboxDao.incrementRetry(event.eventId, System.currentTimeMillis() / 1000)
                    // Never drop events — self-heal is the safety net.
                    // Just log if retries are high so we know something is wrong.
                    if (event.retryCount >= WARN_RETRY_THRESHOLD) {
                        Log.w(TAG, "Event ${event.eventId} has failed ${event.retryCount} retries")
                    }
                }
            }
        }

        private suspend fun pullFromRelays() {
            val groups = groupRepo.getAll()
            for (group in groups) {
                val groupEntity = groupRepo.getGroupEntity(group.id) ?: continue
                val since = if (groupEntity.lastSyncTimestamp > 0) groupEntity.lastSyncTimestamp - 3600 else 0L

                val events = nostrClient.fetchEvents(group.id, since, identity.getPublicKeyHex())
                val existingIds = eventDao.getEventIds(group.id).toSet()
                var newCount = 0

                val groupKey = groupRepo.getGroupKey(group.id) ?: continue
                for (event in events) {
                    if (event.id in existingIds) continue
                    val result =
                        eventProcessor.process(
                            rawEvent = event,
                            knownGroupId = group.id,
                            knownGroupKey = groupKey,
                        )
                    if (result.stored) {
                        ExpenseNotifier.notifyIfNeeded(
                            applicationContext,
                            result.eventType!!,
                            result.decrypted,
                            result.authorHex!!,
                            identity.getPublicKeyHex(),
                            result.groupName ?: "Group",
                        )
                        newCount++
                    }
                }

                if (newCount > 0) {
                    groupRepo.updateLastSync(group.id, System.currentTimeMillis() / 1000)
                    Log.i(TAG, "Pulled $newCount new events for group ${group.name}")
                }

                selfHeal(group.id)
                createSnapshot(group.id)
            }
        }

        companion object {
            private const val TAG = "SyncWorker"
            private const val WARN_RETRY_THRESHOLD = 10
        }
    }
