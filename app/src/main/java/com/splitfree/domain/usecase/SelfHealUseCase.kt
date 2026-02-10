package com.splitfree.domain.usecase

import android.util.Log
import com.splitfree.data.local.EventDao
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.repository.GroupRepository
import javax.inject.Inject

/**
 * Self-healing sync per design doc Section 9.4.
 * Re-publishes local events that are missing from relays.
 * Run once per day per group (during midnight sync).
 */
class SelfHealUseCase @Inject constructor(
    private val eventDao: EventDao,
    private val nostrClient: NostrClient
) {
    suspend operator fun invoke(groupId: String): Int {
        val localEvents = eventDao.getEventsByGroup(groupId)
        val remoteEvents = nostrClient.fetchEvents(groupId, 0)
        val remoteIds = remoteEvents.map { it.id().toHex() }.toSet()

        var republished = 0
        for (event in localEvents) {
            if (event.eventId !in remoteIds) {
                val json = event.originalEventJson ?: continue
                if (nostrClient.publishJson(json)) {
                    republished++
                }
            }
        }
        if (republished > 0) {
            Log.i(TAG, "Self-healed $republished events for group $groupId")
        }
        return republished
    }

    companion object {
        private const val TAG = "SelfHealUseCase"
    }
}
