package com.splitfree.data.ble

import android.util.Log
import com.splitfree.data.local.EventDao
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.EventValidator
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.IdentityManager
import com.splitfree.domain.crypto.hexToBytes
import com.splitfree.domain.crypto.toHex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class BleHandshake(
    val pubkey: String,
    val groups: List<String>,
    val challenge: String = "",       // random challenge for the peer to sign
    val challengeResponse: String = "" // BIP-340 signature proving pubkey ownership
)

@Serializable
data class BleSyncRequest(
    val groupId: String,
    val eventIds: List<String>
)

/**
 * Handles BLE data transfer: handshake with cryptographic authentication,
 * event exchange, and storage. Design doc Section 10.3.
 */
@Singleton
class BleTransfer @Inject constructor(
    private val eventDao: EventDao,
    private val groupRepo: GroupRepository,
    private val encryption: GroupEncryption,
    private val signer: EventSigner,
    private val nearbySync: NearbySync,
    private val identity: IdentityManager
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Pending challenges we sent to peers, keyed by endpointId. */
    private val pendingChallenges = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** Peers that have been authenticated (proved pubkey ownership). */
    private val authenticatedPeers = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Send initial handshake with a random challenge for the peer to sign.
     */
    fun sendHandshake(endpointId: String, pubkey: String, groupIds: List<String>) {
        val challengeBytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val challenge = challengeBytes.toHex()
        pendingChallenges[endpointId] = challenge
        val hs = json.encodeToString(BleHandshake.serializer(), BleHandshake(pubkey, groupIds, challenge = challenge))
        nearbySync.sendPayload(endpointId, byteArrayOf(MSG_HANDSHAKE) + hs.toByteArray())
    }

    /**
     * Send handshake response: includes our signature over the peer's challenge.
     */
    fun sendHandshakeResponse(endpointId: String, pubkey: String, groupIds: List<String>, peerChallenge: String) {
        val privKey = identity.getPrivateKeyBytes()
        val signature: String
        try {
            val challengeBytes = java.security.MessageDigest.getInstance("SHA-256")
                .digest(peerChallenge.toByteArray(Charsets.UTF_8))
            val auxRand = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            val sig = fr.acinq.secp256k1.Secp256k1.signSchnorr(challengeBytes, privKey, auxRand)
            signature = sig.toHex()
        } finally {
            privKey.fill(0)
        }
        val hs = json.encodeToString(BleHandshake.serializer(), BleHandshake(pubkey, groupIds, challengeResponse = signature))
        nearbySync.sendPayload(endpointId, byteArrayOf(MSG_HANDSHAKE) + hs.toByteArray())
    }

    /**
     * Verify a peer's challenge response. Returns true if the peer proved ownership of their pubkey.
     */
    fun verifyHandshake(endpointId: String, handshake: BleHandshake): Boolean {
        val response = handshake.challengeResponse
        if (response.isEmpty()) return false // initial handshake, not a response yet
        val challenge = pendingChallenges.remove(endpointId) ?: return false
        return try {
            val challengeBytes = java.security.MessageDigest.getInstance("SHA-256")
                .digest(challenge.toByteArray(Charsets.UTF_8))
            val pubBytes = handshake.pubkey.hexToBytes()
            val verified = fr.acinq.secp256k1.Secp256k1.verifySchnorr(response.hexToBytes(), challengeBytes, pubBytes)
            if (verified) authenticatedPeers[endpointId] = handshake.pubkey
            verified
        } catch (_: Exception) {
            false
        }
    }

    fun isAuthenticated(endpointId: String): Boolean = endpointId in authenticatedPeers

    fun clearPeer(endpointId: String) {
        pendingChallenges.remove(endpointId)
        authenticatedPeers.remove(endpointId)
    }

    fun sendSyncRequest(endpointId: String, groupId: String, localEventIds: List<String>) {
        if (!isAuthenticated(endpointId)) {
            Log.w(TAG, "Refusing sync request to unauthenticated peer $endpointId")
            return
        }
        val req = json.encodeToString(BleSyncRequest.serializer(), BleSyncRequest(groupId, localEventIds))
        nearbySync.sendPayload(endpointId, byteArrayOf(MSG_SYNC_REQ) + req.toByteArray())
    }

    suspend fun sendMissingEvents(endpointId: String, groupId: String, peerEventIds: Set<String>) {
        if (!isAuthenticated(endpointId)) {
            Log.w(TAG, "Refusing to send events to unauthenticated peer $endpointId")
            return
        }
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
            MSG_EVENT -> {
                if (!isAuthenticated(endpointId)) {
                    Log.w(TAG, "Rejecting event from unauthenticated peer $endpointId")
                    return null
                }
                storeReceivedEvent(String(body))
            }
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

            // group_meta requires membership; group_migrate/key_revocation validated downstream
            if (eventType == "group_meta" && group != null && authorHex !in group.members) {
                Log.w(TAG, "Rejecting BLE group_meta from non-member $authorHex in group $groupId")
                return false
            }

            // Only the group creator can publish group_meta updates
            if (eventType == "group_meta" && group != null && !EventValidator.isGroupMetaAuthorValid(authorHex, group.createdBy)) {
                Log.w(TAG, "Rejecting BLE group_meta from non-creator $authorHex in group $groupId")
                return false
            }

            if (!EventValidator.isWithinRateLimit(authorHex)) {
                Log.w(TAG, "Rate-limiting BLE events from $authorHex")
                return false
            }

            val groupKey = groupRepo.getGroupKey(groupId) ?: return false
            val encrypted = event.content
            val decrypted = try { encryption.decrypt(encrypted, groupKey) } catch (_: Exception) { null }

            // Validate corrections/deletions come from the original expense creator
            if (eventType == "expense_correction" || eventType == "expense_delete") {
                val originalCreator = expenseUuid?.let { eventDao.getExpenseByUuid(it)?.pubkey }
                if (!EventValidator.isCorrectionAuthorValid(eventType, authorHex, originalCreator)) {
                    Log.w(TAG, "Rejecting BLE ${eventType} ${event.id}: author $authorHex is not the original creator")
                    return false
                }
            }

            // Reject replayed expenses that were previously deleted (tombstone check)
            if (eventType == "expense" && expenseUuid != null) {
                val deletedUuids = eventDao.getDeletedExpenseUuids(groupId).toSet()
                if (EventValidator.isDeletedExpense(eventType, expenseUuid, deletedUuids)) {
                    Log.w(TAG, "Rejecting BLE replayed deleted expense: $expenseUuid")
                    return false
                }
            }

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
        if (!isAuthenticated(endpointId)) {
            Log.w(TAG, "Refusing to send binary events to unauthenticated peer $endpointId")
            return
        }
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
     * Requires the peer to be authenticated via Schnorr handshake.
     */
    suspend fun processBinaryPayload(endpointId: String, data: ByteArray): BlePacket? {
        if (!isAuthenticated(endpointId)) {
            Log.w(TAG, "Rejecting binary payload from unauthenticated peer $endpointId")
            return null
        }
        // Try direct decode first
        val packet = BleProtocol.decode(data)
        if (packet != null) {
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
