package com.splitfree.domain.usecase

import android.util.Log
import com.splitfree.data.local.EventDao
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.nostr.NostrFilter
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.IdentityManager
import javax.inject.Inject

/**
 * Self-healing sync per design doc Section 9.4.
 * Re-publishes local events that are missing from relays.
 * Run once per day per group (during midnight sync).
 */
class SelfHealUseCase @Inject constructor(
    private val eventDao: EventDao,
    private val nostrClient: NostrClient,
    private val signer: EventSigner,
    private val identity: IdentityManager
) {
    suspend operator fun invoke(groupId: String): Int {
        val localEvents = eventDao.getEventsByGroup(groupId)
        if (localEvents.isEmpty()) return 0

        // Fetch what the relay has for this group using BOTH old (#d) and new (#g) tag formats
        // so we don't needlessly re-publish events that are already there under the old format
        val oldestLocal = localEvents.minOfOrNull { it.createdAt } ?: 0L
        val since = if (oldestLocal > 0) oldestLocal - 86400 else 0L // 1 day buffer
        val remoteByGroup = nostrClient.fetchEvents(groupId, since)
        val remoteIds = remoteByGroup.map { it.id }.toMutableSet()

        var republished = 0
        for (event in localEvents) {
            if (event.eventId in remoteIds) continue
            val json = event.originalEventJson ?: continue
            if (nostrClient.publishJson(json)) {
                republished++
            }
            // Throttle to avoid relay-side rate limiting
            if (republished % 10 == 0) {
                kotlinx.coroutines.delay(1000)
            }
            // Cap to prevent excessive resource usage on large groups
            if (republished >= MAX_REPUBLISH_PER_RUN) {
                Log.i(TAG, "Self-heal capped at $MAX_REPUBLISH_PER_RUN for group $groupId, will continue next run")
                break
            }
        }
        if (republished > 0) {
            Log.i(TAG, "Self-healed $republished events for group $groupId")
        }
        return republished
    }

    companion object {
        private const val TAG = "SelfHealUseCase"
        private const val MAX_REPUBLISH_PER_RUN = 200
    }
}
