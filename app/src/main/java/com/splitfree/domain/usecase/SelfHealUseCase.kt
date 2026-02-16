package com.splitfree.domain.usecase

import com.splitfree.util.DebugLog as Log
import com.splitfree.data.local.EventDao
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.IdentityManager
import javax.inject.Inject

/**
 * Self-healing sync per design doc Section 9.4.
 * Re-publishes local events that are missing from relays.
 * Processes in batches to avoid relay rate limiting while ensuring all events are published.
 */
class SelfHealUseCase
    @Inject
    constructor(
        private val eventDao: EventDao,
        private val nostrClient: NostrClient,
        private val signer: EventSigner,
        private val identity: IdentityManager,
        private val groupRepo: com.splitfree.data.repository.GroupRepository,
    ) {
        suspend operator fun invoke(groupId: String): Int {
            if (!nostrClient.isConnected) {
                Log.d(TAG, "No relay connection — skipping self-heal for $groupId")
                return 0
            }

            val group = groupRepo.getById(groupId) ?: return 0
            val currentMembers = group.members.toSet()

            val localEvents = eventDao.getEventsByGroup(groupId)
            if (localEvents.isEmpty()) return 0

            val oldestLocal = localEvents.minOfOrNull { it.createdAt } ?: 0L
            val since = if (oldestLocal > 0) oldestLocal - 86400 else 0L
            val myPubkey = identity.getPublicKeyHex()
            val remoteByGroup = nostrClient.fetchEvents(groupId, since, myPubkey)
            val remoteIds = remoteByGroup.map { it.id }.toSet()

            val missing = localEvents.filter {
                it.eventId !in remoteIds
                    && it.originalEventJson != null
                    && it.pubkey in currentMembers
            }
            if (missing.isEmpty()) return 0

            var totalRepublished = 0
            val batches = missing.chunked(BATCH_SIZE)
            for ((index, batch) in batches.withIndex()) {
                var batchCount = 0
                for (event in batch) {
                    if (nostrClient.publishJson(event.originalEventJson!!)) {
                        batchCount++
                    }
                    // Intra-batch throttle: pause every 10 publishes to avoid rate limiting
                    if ((totalRepublished + batchCount) % THROTTLE_EVERY == 0 && batchCount > 0) {
                        kotlinx.coroutines.delay(THROTTLE_DELAY_MS)
                    }
                }
                totalRepublished += batchCount

                // Safety cap to prevent runaway in extreme cases
                if (totalRepublished >= ABSOLUTE_CAP) {
                    Log.i(TAG, "Self-heal capped at $ABSOLUTE_CAP for group $groupId, remaining next run")
                    break
                }

                // Inter-batch cooldown (skip after last batch)
                if (index < batches.size - 1) {
                    Log.d(TAG, "Self-heal batch ${index + 1}/${batches.size}: $totalRepublished/${missing.size} for group $groupId")
                    kotlinx.coroutines.delay(BATCH_COOLDOWN_MS)
                }
            }
            if (totalRepublished > 0) {
                Log.i(TAG, "Self-healed $totalRepublished/${missing.size} events for group $groupId")
            }
            return totalRepublished
        }

        companion object {
            private const val TAG = "SelfHealUseCase"
            const val BATCH_SIZE = 50
            const val THROTTLE_EVERY = 10
            const val THROTTLE_DELAY_MS = 1000L
            const val BATCH_COOLDOWN_MS = 3000L
            const val ABSOLUTE_CAP = 1000
        }
    }
