package com.splitfree.data.ble

import com.splitfree.data.identity.IdentityManager
import com.splitfree.data.local.dao.EventDao
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.util.hexToBytes
import com.splitfree.domain.util.toHex
import com.splitfree.sync.event.EventProcessor
import com.splitfree.util.DebugLog as Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * BLE handshake payload: challenge + optional signature for Schnorr authentication.
 */
@Serializable
data class BleHandshake(
    val pubkey: String,
    val groups: List<String>,
    // Random challenge for the peer to sign
    val challenge: String = "",
    // BIP-340 signature proving pubkey ownership
    val challengeResponse: String = ""
)

/**
 * Request to sync specific events for a group over BLE.
 */
@Serializable
data class BleSyncRequest(val groupId: String, val eventIds: List<String>)

/**
 * Handles BLE data transfer: Schnorr-authenticated handshake, event exchange, and storage.
 *
 * Connection flow:
 * 1. Both peers exchange random 32-byte challenges (hex-encoded)
 * 2. Each peer signs a domain-separated transcript of the other's challenge with their Nostr
 *    private key (BIP-340), see [authTranscriptHash]
 * 3. After mutual authentication, group IDs are exchanged for sync discovery
 * 4. Missing events are transferred using the compact [BleProtocol] binary format
 *
 * The signed message is never the raw challenge: an unauthenticated peer controls the challenge
 * string, and a Nostr signature is a Schnorr signature over `SHA256(NIP-01 serialization)`.
 * Signing `SHA256(challenge)` directly would let a peer submit a NIP-01 serialization as its
 * "challenge" and receive a valid signature over an arbitrary Nostr event. The transcript hash
 * is tagged so it cannot collide with any event ID, and challenges must be exactly 32 bytes of
 * lowercase hex so no structured input can reach the signer.
 */
@Singleton
class BleTransfer
@Inject
constructor(
    private val eventDao: EventDao,
    private val groupRepo: GroupRepository,
    private val signer: EventSigner,
    private val nearbySync: NearbySync,
    private val identity: IdentityManager,
    private val eventProcessor: EventProcessor
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Pending challenges we sent to peers, keyed by endpointId. */
    private val pendingChallenges = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Peers that have been authenticated (proved pubkey ownership). */
    private val authenticatedPeers = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val handshakeTimeouts = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Pubkey each peer claimed in its first handshake message, keyed by endpointId. A later
     * handshake leg from the same endpoint must carry the same pubkey, so an endpoint cannot
     * switch identities between the challenge and the response.
     */
    private val peerClaimedPubkey = java.util.concurrent.ConcurrentHashMap<String, String>()

    companion object {
        private const val TAG = "BleTransfer"
        const val MSG_HANDSHAKE: Byte = 0x01
        const val MSG_SYNC_REQ: Byte = 0x02
        const val MSG_EVENT: Byte = 0x03
        const val MSG_GROUP_IDS: Byte = 0x04
        const val HANDSHAKE_TIMEOUT_MS = 10_000L

        /**
         * Domain-separation tag for the handshake transcript. A NIP-01 serialization always begins
         * with `[0,`, so a SHA-256 preimage that begins with this tag can never be a Nostr event ID.
         */
        private const val AUTH_TAG = "splitfree-ble-auth-v1"
        private const val HEX_ALPHABET = "0123456789abcdef"
    }

    /** Exactly 32 bytes as lowercase hex: the required shape of a challenge and of an x-only pubkey. */
    private fun isHex32(s: String) = s.length == 64 && s.all { it in HEX_ALPHABET }

    private fun isValidChallenge(c: String) = isHex32(c)

    private fun isValidPubkey(p: String) = isHex32(p)

    /**
     * Hash of the handshake transcript that is actually signed:
     *
     *     SHA256(AUTH_TAG || 0x00 || challenge(32) || signerPubkey(32) || verifierPubkey(32))
     *
     * The tag separates this from every other use of the Nostr key (in particular NIP-01 event
     * IDs). Binding the signer's pubkey stops a signature from being presented under a different
     * identity; binding the verifier's pubkey stops it from being replayed to a third party who
     * issued the same challenge.
     */
    private fun authTranscriptHash(challenge: String, signerPubkeyHex: String, verifierPubkeyHex: String): ByteArray {
        require(isValidChallenge(challenge)) { "challenge must be 32 bytes of lowercase hex" }
        require(isValidPubkey(signerPubkeyHex)) { "signer pubkey must be 32 bytes of lowercase hex" }
        require(isValidPubkey(verifierPubkeyHex)) { "verifier pubkey must be 32 bytes of lowercase hex" }
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(AUTH_TAG.toByteArray(Charsets.UTF_8))
        md.update(0x00.toByte())
        md.update(challenge.hexToBytes())
        md.update(signerPubkeyHex.hexToBytes())
        md.update(verifierPubkeyHex.hexToBytes())
        return md.digest()
    }

    /**
     * Sign the transcript for [peerChallenge] with our long-term key. Inputs must already be
     * validated; the private key is zeroed after use.
     */
    private fun signAuthTranscript(peerChallenge: String, ourPubkey: String, peerPubkey: String): String {
        val hash = authTranscriptHash(peerChallenge, signerPubkeyHex = ourPubkey, verifierPubkeyHex = peerPubkey)
        val privKey = identity.getPrivateKeyBytes()
        try {
            val auxRand = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            return fr.acinq.secp256k1.Secp256k1
                .signSchnorr(hash, privKey, auxRand)
                .toHex()
        } finally {
            privKey.fill(0)
        }
    }

    /** True if all handshake inputs have the required shape; logs and returns false otherwise. */
    private fun validateSigningInputs(
        endpointId: String,
        ourPubkey: String,
        peerChallenge: String,
        peerPubkey: String
    ): Boolean {
        if (!isValidChallenge(peerChallenge)) {
            Log.w(TAG, "Refusing to sign for $endpointId: challenge is not 32 bytes of lowercase hex")
            return false
        }
        if (!isValidPubkey(peerPubkey)) {
            Log.w(TAG, "Refusing to sign for $endpointId: peer pubkey is not 32 bytes of lowercase hex")
            return false
        }
        if (!isValidPubkey(ourPubkey)) {
            Log.w(TAG, "Refusing to sign for $endpointId: our pubkey is not 32 bytes of lowercase hex")
            return false
        }
        return true
    }

    /**
     * Send initial handshake with a random challenge for the peer to sign.
     * Group IDs are withheld until after mutual authentication.
     */
    fun sendHandshake(endpointId: String, pubkey: String, groupIds: List<String>) {
        val challengeBytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val challenge = challengeBytes.toHex()
        pendingChallenges[endpointId] = challenge
        // Send empty groups list — real group IDs sent after authentication
        val hs = json.encodeToString(
            BleHandshake.serializer(),
            BleHandshake(pubkey, emptyList(), challenge = challenge)
        )
        nearbySync.sendPayload(endpointId, byteArrayOf(MSG_HANDSHAKE) + hs.toByteArray())
        // Schedule handshake timeout
        handshakeTimeouts[endpointId] = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MS
    }

    /**
     * Send handshake response: includes our signature over the peer's challenge
     * AND a new challenge for the peer to sign (mutual authentication).
     *
     * [peerPubkey] is the pubkey the peer claimed in its initial handshake; it is bound into the
     * signed transcript as the verifier and recorded so later legs cannot switch identity.
     * Nothing is signed or sent if [peerChallenge] or [peerPubkey] is not 32 bytes of lowercase hex.
     */
    fun sendHandshakeResponse(
        endpointId: String,
        pubkey: String,
        groupIds: List<String>,
        peerChallenge: String,
        peerPubkey: String
    ) {
        if (!validateSigningInputs(endpointId, pubkey, peerChallenge, peerPubkey)) return
        peerClaimedPubkey[endpointId] = peerPubkey

        val challengeBytes2 = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val ourChallenge = challengeBytes2.toHex()
        pendingChallenges[endpointId] = ourChallenge

        val signature = signAuthTranscript(peerChallenge, ourPubkey = pubkey, peerPubkey = peerPubkey)
        val hs = json.encodeToString(
            BleHandshake.serializer(),
            BleHandshake(pubkey, emptyList(), challenge = ourChallenge, challengeResponse = signature)
        )
        nearbySync.sendPayload(endpointId, byteArrayOf(MSG_HANDSHAKE) + hs.toByteArray())
    }

    /**
     * Send the final handshake leg: sign the responder's challenge to complete mutual auth.
     *
     * [peerPubkey] is the responder's (already verified) pubkey, bound into the transcript as the
     * verifier. Nothing is signed or sent if [peerChallenge] or [peerPubkey] is malformed.
     */
    fun sendChallengeResponse(endpointId: String, pubkey: String, peerChallenge: String, peerPubkey: String) {
        if (!validateSigningInputs(endpointId, pubkey, peerChallenge, peerPubkey)) return

        val signature = signAuthTranscript(peerChallenge, ourPubkey = pubkey, peerPubkey = peerPubkey)
        val hs = json.encodeToString(
            BleHandshake.serializer(),
            BleHandshake(pubkey, emptyList(), challengeResponse = signature)
        )
        nearbySync.sendPayload(endpointId, byteArrayOf(MSG_HANDSHAKE) + hs.toByteArray())
    }

    /**
     * Verify a peer's challenge response. Returns true if the peer proved ownership of their pubkey.
     *
     * The signature must cover [authTranscriptHash] of the challenge we issued, with the peer as
     * signer and us as verifier. The pending challenge is consumed on every attempt, and the peer's
     * pubkey must match whatever this endpoint claimed in its first handshake message.
     */
    fun verifyHandshake(endpointId: String, handshake: BleHandshake): Boolean {
        val response = handshake.challengeResponse
        if (response.isEmpty()) return false // initial handshake, not a response yet
        val challenge = pendingChallenges.remove(endpointId) ?: return false
        if (!isValidChallenge(challenge)) {
            Log.w(TAG, "Rejecting handshake from $endpointId: pending challenge is malformed")
            return false
        }
        if (!isValidPubkey(handshake.pubkey)) {
            Log.w(TAG, "Rejecting handshake from $endpointId: pubkey is not 32 bytes of lowercase hex")
            return false
        }
        // Bind the endpoint to the first pubkey it claimed so it cannot switch identities mid-handshake.
        val claimed = peerClaimedPubkey.putIfAbsent(endpointId, handshake.pubkey)
        if (claimed != null && claimed != handshake.pubkey) {
            Log.w(TAG, "Rejecting handshake from $endpointId: pubkey changed mid-handshake")
            return false
        }
        return try {
            val hash =
                authTranscriptHash(
                    challenge,
                    signerPubkeyHex = handshake.pubkey,
                    verifierPubkeyHex = identity.getPublicKeyHex()
                )
            val verified =
                fr.acinq.secp256k1.Secp256k1
                    .verifySchnorr(response.hexToBytes(), hash, handshake.pubkey.hexToBytes())
            if (verified) authenticatedPeers[endpointId] = handshake.pubkey
            verified
        } catch (_: Exception) {
            false
        }
    }

    fun isAuthenticated(endpointId: String): Boolean = authenticatedPeers.containsKey(endpointId)

    /**
     * Send group IDs to an authenticated peer for sync discovery.
     */
    fun sendGroupIds(endpointId: String, groupIds: List<String>) {
        if (!isAuthenticated(endpointId)) {
            Log.w(TAG, "Refusing to send group IDs to unauthenticated peer $endpointId")
            return
        }
        val payload = json.encodeToString(ListSerializer(String.serializer()), groupIds)
        nearbySync.sendPayload(endpointId, byteArrayOf(MSG_GROUP_IDS) + payload.toByteArray())
    }

    fun isHandshakeTimedOut(endpointId: String): Boolean {
        val deadline = handshakeTimeouts[endpointId] ?: return false
        return System.currentTimeMillis() > deadline
    }

    fun clearPeer(endpointId: String) {
        pendingChallenges.remove(endpointId)
        authenticatedPeers.remove(endpointId)
        handshakeTimeouts.remove(endpointId)
        peerClaimedPubkey.remove(endpointId)
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
        val peerPubkey = authenticatedPeers[endpointId]
        val group = groupRepo.getById(groupId)
        if (peerPubkey == null || group == null || peerPubkey !in group.members) {
            Log.w(TAG, "Refusing to send events: peer $endpointId is not a member of group $groupId")
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
            MSG_HANDSHAKE -> {
                decodeOrNull(BleHandshake.serializer(), body)
            }

            MSG_SYNC_REQ -> {
                val req = decodeOrNull(BleSyncRequest.serializer(), body) ?: return null
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

            MSG_GROUP_IDS -> {
                if (!isAuthenticated(endpointId)) {
                    Log.w(TAG, "Rejecting group IDs from unauthenticated peer $endpointId")
                    return null
                }
                decodeOrNull(ListSerializer(String.serializer()), body)
            }

            else -> {
                null
            }
        }
    }

    /**
     * Parse a peer-supplied payload. A peer is untrusted here — MSG_HANDSHAKE is handled
     * before authentication — so malformed bytes must be discarded rather than thrown to
     * the caller, which collects this in a flow whose collector would die with it.
     */
    private fun <T> decodeOrNull(serializer: DeserializationStrategy<T>, body: ByteArray): T? = try {
        json.decodeFromString(serializer, String(body, Charsets.UTF_8))
    } catch (e: IllegalArgumentException) {
        // Covers SerializationException, which extends IllegalArgumentException.
        Log.w(TAG, "Discarding unparseable BLE payload from peer: ${e.message}")
        null
    }

    private suspend fun storeReceivedEvent(eventJson: String): Boolean {
        return try {
            val event = NostrEvent.fromJson(eventJson) ?: return false
            val result = eventProcessor.process(rawEvent = event)
            result.stored
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to store BLE event: ${e.message}")
            false
        }
    }

    /**
     * Send missing events using binary protocol (compact, with fragmentation).
     */
    suspend fun sendMissingEventsBinary(
        endpointId: String,
        groupId: String,
        peerEventIds: Set<String>,
        senderPubkey: String
    ) {
        if (!isAuthenticated(endpointId)) {
            Log.w(TAG, "Refusing to send binary events to unauthenticated peer $endpointId")
            return
        }
        val peerPubkey = authenticatedPeers[endpointId]
        val group = groupRepo.getById(groupId)
        if (peerPubkey == null || group == null || peerPubkey !in group.members) {
            Log.w(TAG, "Refusing to send binary events: peer $endpointId is not a member of group $groupId")
            return
        }
        val localEvents = eventDao.getEventsByGroup(groupId)
        var sent = 0
        for (event in localEvents) {
            if (event.eventId !in peerEventIds) {
                val eventJson = event.originalEventJson ?: continue
                val msgType = MessageType.fromEventType(event.eventType) ?: MessageType.EXPENSE
                val packet =
                    BleProtocol.encode(
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
        val assembled = FragmentManager.addFragment(endpointId, data)
        if (assembled != null) {
            val fullPacket = BleProtocol.decode(assembled)
            if (fullPacket != null) {
                storeReceivedEvent(String(fullPacket.payload))
                return fullPacket
            }
        }
        return null
    }
}
