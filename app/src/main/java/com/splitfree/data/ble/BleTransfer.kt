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
            val event = com.splitfree.domain.crypto.NostrEvent.fromJson(eventJson) ?: return false
            if (!signer.verify(event)) return false

            if (!EventValidator.isTimestampValid(event.createdAt)) {
                Log.w(TAG, "Rejecting BLE event with invalid timestamp: ${event.id}")
                return false
            }

            val eventId = event.id
            if (eventDao.getEvent(eventId) != null) return false

            var groupId: String? = null
            var eventType = "unknown"
            var expenseUuid: String? = null
            for (tag in event.tags) {
                if (tag.size >= 2) when (tag[0]) {
                    "g" -> groupId = tag[1]
                    "t" -> eventType = tag[1]
                    "e" -> expenseUuid = tag[1]
                }
            }
            groupId ?: return false

            val authorHex = event.pubkey
            val group = groupRepo.getById(groupId)
            if (eventType != "group_meta" && eventType != "group_migrate" && eventType != "key_revocation" && group != null && authorHex !in group.members) {
                Log.w(TAG, "Rejecting BLE event from non-member $authorHex in group $groupId")
                return false
            }

            val groupKey = groupRepo.getGroupKey(groupId) ?: return false
            val encrypted = event.content
            val decrypted = try { encryption.decrypt(encrypted, groupKey) } catch (_: Exception) { null }

            eventDao.insert(EventEntity(
                eventId = eventId, groupId = groupId,
                pubkey = authorHex,
                createdAt = event.createdAt,
                kind = 30078, contentEncrypted = encrypted,
                contentDecrypted = decrypted, eventType = eventType,
                expenseUuid = expenseUuid, sig = event.sig,
                receivedAt = System.currentTimeMillis() / 1000,
                originalEventJson = eventJson
            ))
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to store BLE event: ${e.message}")
            false
        }
    }

    /**
     * Send missing events using binary protocol (compact, with fragmentation).
     */
    suspend fun sendMissingEventsBinary(endpointId: String, groupId: String, peerEventIds: Set<String>, senderPubkey: String) {
        val localEvents = eventDao.getEventsByGroup(groupId)
        var sent = 0
        for (event in localEvents) {
            if (event.eventId !in peerEventIds) {
                val eventJson = event.originalEventJson ?: continue
                val msgType = MessageType.fromEventType(event.eventType) ?: MessageType.EXPENSE
                val packet = BleProtocol.encode(
                    type = msgType,
                    payload = eventJson.toByteArray(),
                    senderPubkey = senderPubkey,
                    groupId = groupId
                )
                val fragments = FragmentManager.fragment(packet)
                for (frag in fragments) {
                    nearbySync.sendPayload(endpointId, frag)
                }
                sent++
            }
        }
        Log.i(TAG, "Sent $sent missing events (binary) for group $groupId to $endpointId")
    }

    /**
     * Process a binary protocol packet. Returns the decoded BlePacket or null.
     */
    suspend fun processBinaryPayload(data: ByteArray): BlePacket? {
        // Try direct decode first
        val packet = BleProtocol.decode(data)
        if (packet != null) {
            // Store the event from the payload
            storeReceivedEvent(String(packet.payload))
            return packet
        }
        // Try as fragment
        val assembled = FragmentManager.addFragment(data)
        if (assembled != null) {
            val fullPacket = BleProtocol.decode(assembled)
            if (fullPacket != null) {
                storeReceivedEvent(String(fullPacket.payload))
                return fullPacket
            }
        }
        return null
    }

    companion object {
        private const val TAG = "BleTransfer"
        const val MSG_HANDSHAKE: Byte = 0x01
        const val MSG_SYNC_REQ: Byte = 0x02
        const val MSG_EVENT: Byte = 0x03
    }
}
