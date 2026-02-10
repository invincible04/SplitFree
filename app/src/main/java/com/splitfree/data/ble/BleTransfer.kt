package com.splitfree.data.ble

import android.util.Log
import com.splitfree.data.local.EventDao
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.EventValidator
import com.splitfree.domain.crypto.GroupEncryption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import rust.nostr.sdk.Event
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class BleHandshake(
    val pubkey: String,
    val groups: List<String>
)

@Serializable
data class BleSyncRequest(
    val groupId: String,
    val eventIds: List<String>
)

/**
 * Handles BLE data transfer: handshake, event exchange, and storage.
 * Design doc Section 10.3.
 */
@Singleton
class BleTransfer @Inject constructor(
    private val eventDao: EventDao,
    private val groupRepo: GroupRepository,
    private val encryption: GroupEncryption,
    private val signer: EventSigner,
    private val nearbySync: NearbySync
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun sendHandshake(endpointId: String, pubkey: String, groupIds: List<String>) {
        val hs = json.encodeToString(BleHandshake.serializer(), BleHandshake(pubkey, groupIds))
        nearbySync.sendPayload(endpointId, byteArrayOf(MSG_HANDSHAKE) + hs.toByteArray())
    }

    fun sendSyncRequest(endpointId: String, groupId: String, localEventIds: List<String>) {
        val req = json.encodeToString(BleSyncRequest.serializer(), BleSyncRequest(groupId, localEventIds))
        nearbySync.sendPayload(endpointId, byteArrayOf(MSG_SYNC_REQ) + req.toByteArray())
    }

    suspend fun sendMissingEvents(endpointId: String, groupId: String, peerEventIds: Set<String>) {
        val localEvents = eventDao.getEventsByGroup(groupId)
        var sent = 0
        for (event in localEvents) {
            if (event.eventId !in peerEventIds) {
                val eventJson = event.originalEventJson ?: continue
                nearbySync.sendPayload(endpointId, byteArrayOf(MSG_EVENT) + eventJson.toByteArray())
                sent++
            }
        }
        Log.i(TAG, "Sent $sent missing events for group $groupId to $endpointId")
    }

    suspend fun processPayload(endpointId: String, data: ByteArray): Any? {
        if (data.isEmpty()) return null
        val type = data[0]
        val body = data.copyOfRange(1, data.size)
        return when (type) {
            MSG_HANDSHAKE -> json.decodeFromString<BleHandshake>(String(body))
            MSG_SYNC_REQ -> {
                val req = json.decodeFromString<BleSyncRequest>(String(body))
                sendMissingEvents(endpointId, req.groupId, req.eventIds.toSet())
                req
            }
            MSG_EVENT -> storeReceivedEvent(String(body))
            else -> null
        }
    }

    private suspend fun storeReceivedEvent(eventJson: String): Boolean {
        return try {
            val event = Event.fromJson(eventJson)
            if (!signer.verify(event)) return false

            // Reject future/stale timestamps (design doc Section 13.7)
            val createdAt = event.createdAt().asSecs().toLong()
            if (!EventValidator.isTimestampValid(createdAt)) {
                Log.w(TAG, "Rejecting BLE event with invalid timestamp: ${event.id().toHex()}")
                return false
            }

            val eventId = event.id().toHex()
            if (eventDao.getEvent(eventId) != null) return false

            var groupId: String? = null
            var eventType = "unknown"
            var expenseUuid: String? = null
            for (tag in event.tags().toVec()) {
                val items = tag.asVec()
                if (items.size >= 2) when (items[0]) {
                    "d" -> groupId = items[1]
                    "t" -> eventType = items[1]
                    "e" -> expenseUuid = items[1]
                }
            }
            groupId ?: return false

            // Reject events from non-members (design doc Section 13.2)
            val authorHex = event.author().toHex()
            val group = groupRepo.getById(groupId)
            if (eventType != "group_meta" && group != null && authorHex !in group.members) {
                Log.w(TAG, "Rejecting BLE event from non-member $authorHex in group $groupId")
                return false
            }

            val groupKey = groupRepo.getGroupKey(groupId) ?: return false
            val encrypted = event.content()
            val decrypted = try { encryption.decrypt(encrypted, groupKey) } catch (_: Exception) { null }

            eventDao.insert(EventEntity(
                eventId = eventId, groupId = groupId,
                pubkey = authorHex,
                createdAt = createdAt,
                kind = 30078, contentEncrypted = encrypted,
                contentDecrypted = decrypted, eventType = eventType,
                expenseUuid = expenseUuid, sig = event.signature().toHex(),
                receivedAt = System.currentTimeMillis() / 1000,
                originalEventJson = eventJson
            ))
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to store BLE event: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "BleTransfer"
        const val MSG_HANDSHAKE: Byte = 0x01
        const val MSG_SYNC_REQ: Byte = 0x02
        const val MSG_EVENT: Byte = 0x03
    }
}
