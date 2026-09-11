package com.splitfree.domain.usecase.sync

import com.splitfree.domain.crypto.GiftWrapService
import com.splitfree.domain.repository.EventRepositoryContract
import com.splitfree.domain.repository.EventSnapshot
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.NostrClientContract
import com.splitfree.util.DebugLog as Log
import javax.inject.Inject
import kotlinx.coroutines.delay

/**
 * Re-publishes local events that are missing from relays.
 * Processes in batches to avoid relay rate limiting while ensuring all events are published.
 */
class SelfHealUseCase
@Inject
constructor(
    private val eventRepo: EventRepositoryContract,
    private val nostrClient: NostrClientContract,
    private val giftWrap: GiftWrapService,
    private val identity: IdentityContract,
    private val groupRepo: GroupRepositoryContract
) {
    suspend operator fun invoke(groupId: String): Int {
        if (!nostrClient.isConnected) {
            Log.d(TAG, "No relay connection — skipping self-heal for $groupId")
            return 0
        }
        if (giftWrap.enabled) {
            Log.d(TAG, "Gift wrap enabled — skipping self-heal for $groupId (outbox handles retries)")
            return 0
        }

        val group = groupRepo.getById(groupId) ?: return 0
        val currentMembers = group.members.toSet()

        val localEvents = eventRepo.getEventsByGroup(groupId)
        if (localEvents.isEmpty()) return 0

        val oldestLocal = localEvents.minOfOrNull { it.createdAt } ?: 0L
        val since = if (oldestLocal > 0) oldestLocal - 86400 else 0L
        val myPubkey = identity.getPublicKeyHex()
        val remoteIds = nostrClient.fetchEventIds(groupId, since, myPubkey)

        // Rows that arrived as NIP-59 rumors (empty or `seal:` sig) are not re-publishable: a
        // relay/peer cannot verify them and their author is responsible for their delivery.
        val missing =
            localEvents.filter {
                it.eventId !in remoteIds &&
                    it.originalEventJson != null &&
                    EventSnapshot.isThirdPartyVerifiable(it.sig) &&
                    it.pubkey in currentMembers
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
                    delay(THROTTLE_DELAY_MS)
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
                Log.d(
                    TAG,
                    "Self-heal batch ${index + 1}/${batches.size}: $totalRepublished/${missing.size} for group $groupId"
                )
                delay(BATCH_COOLDOWN_MS)
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
