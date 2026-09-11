package com.splitfree.data.ble

import com.splitfree.data.identity.IdentityManager
import com.splitfree.data.local.dao.EventDao
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.EventSnapshot
import com.splitfree.domain.util.hexToBytes
import com.splitfree.domain.util.toHex
import com.splitfree.sync.event.EventProcessor
import fr.acinq.secp256k1.Secp256k1
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BleTransferTest {
    private val eventDao = mockk<EventDao>(relaxed = true)
    private val groupRepo = mockk<GroupRepository>(relaxed = true)
    private val signer = mockk<EventSigner>()
    private val nearbySync = mockk<NearbySync>(relaxed = true)
    private val identity = mockk<IdentityManager>()
    private val eventProcessor = mockk<EventProcessor>()

    private lateinit var transfer: BleTransfer
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        transfer = BleTransfer(eventDao, groupRepo, signer, nearbySync, identity, eventProcessor)
    }

    @After
    fun teardown() {
        unmockkStatic(android.util.Log::class)
    }

    /**
     * One side of a BLE handshake with a real secp256k1 identity. Payloads handed to
     * [NearbySync.sendPayload] are captured so the other side can consume them.
     */
    private class Peer(privHex: String) {
        val priv: ByteArray = privHex.hexToBytes()
        val pub: String = NostrEvent.pubkeyFromPrivkey(priv)
        val sent = mutableListOf<ByteArray>()
        val nearbySync = mockk<NearbySync>()
        val identity = mockk<IdentityManager>()
        val eventDao = mockk<EventDao>(relaxed = true)
        val groupRepo = mockk<GroupRepository>(relaxed = true)
        val transfer: BleTransfer

        init {
            every { nearbySync.sendPayload(any(), capture(sent)) } just Runs
            every { identity.getPublicKeyHex() } returns pub
            // BleTransfer zeroes the key after signing, so hand out a fresh copy each time.
            every { identity.getPrivateKeyBytes() } answers { priv.copyOf() }
            transfer =
                BleTransfer(
                    eventDao,
                    groupRepo,
                    mockk(),
                    nearbySync,
                    identity,
                    mockk()
                )
        }

        fun lastSent(): ByteArray = sent.last()

        /** Feed a wire payload from [fromEndpoint] into this peer and return the parsed handshake. */
        fun receive(fromEndpoint: String, data: ByteArray): BleHandshake = runBlocking {
            transfer.processPayload(fromEndpoint, data) as BleHandshake
        }
    }

    private companion object {
        const val ALICE_PRIV = "0101010101010101010101010101010101010101010101010101010101010101"
        const val BOB_PRIV = "0202020202020202020202020202020202020202020202020202020202020202"
        const val MALLORY_PRIV = "0303030303030303030303030303030303030303030303030303030303030303"
        val HEX32 = "cd".repeat(32)
        val NIP01_SHAPED_CHALLENGE = "[0,\"${"ab".repeat(32)}\",1700000000,1,[],\"\"]"
    }

    // --- Handshake ---

    @Test
    fun `sendHandshake sends MSG_HANDSHAKE payload`() {
        transfer.sendHandshake("ep1", "pubkey", listOf("g1"))
        verify { nearbySync.sendPayload("ep1", match { it[0] == BleTransfer.MSG_HANDSHAKE }) }
    }

    @Test
    fun `isAuthenticated returns false initially`() {
        assertFalse(transfer.isAuthenticated("ep1"))
    }

    @Test
    fun `verifyHandshake returns false for empty response`() {
        val hs = BleHandshake("pub", emptyList(), challengeResponse = "")
        assertFalse(transfer.verifyHandshake("ep1", hs))
    }

    @Test
    fun `verifyHandshake returns false without pending challenge`() {
        val hs = BleHandshake("pub", emptyList(), challengeResponse = "aabb")
        assertFalse(transfer.verifyHandshake("ep1", hs))
    }

    @Test
    fun `isHandshakeTimedOut returns false without deadline`() {
        assertFalse(transfer.isHandshakeTimedOut("ep1"))
    }

    @Test
    fun `clearPeer removes all state`() {
        transfer.sendHandshake("ep1", "pub", listOf("g1"))
        transfer.clearPeer("ep1")
        assertFalse(transfer.isAuthenticated("ep1"))
        assertFalse(transfer.isHandshakeTimedOut("ep1"))
    }

    // --- Mutual authentication (Schnorr transcript) ---

    @Test
    fun `three-leg mutual authentication round trip succeeds`() {
        val alice = Peer(ALICE_PRIV) // initiator
        val bob = Peer(BOB_PRIV) // responder

        // Leg 1: Alice -> Bob, challenge only.
        alice.transfer.sendHandshake("bob", alice.pub, emptyList())
        val leg1 = bob.receive("alice", alice.lastSent())
        assertEquals(alice.pub, leg1.pubkey)
        assertEquals(64, leg1.challenge.length)
        assertTrue(leg1.challengeResponse.isEmpty())

        // Leg 2: Bob -> Alice, signs Alice's challenge and issues a counter-challenge.
        bob.transfer.sendHandshakeResponse("alice", bob.pub, emptyList(), leg1.challenge, leg1.pubkey)
        val leg2 = alice.receive("bob", bob.lastSent())
        assertEquals(bob.pub, leg2.pubkey)
        assertEquals(64, leg2.challenge.length)
        assertTrue(alice.transfer.verifyHandshake("bob", leg2))
        assertTrue(alice.transfer.isAuthenticated("bob"))
        assertFalse(bob.transfer.isAuthenticated("alice"))

        // Leg 3: Alice -> Bob, signs Bob's counter-challenge.
        alice.transfer.sendChallengeResponse("bob", alice.pub, leg2.challenge, leg2.pubkey)
        val leg3 = bob.receive("alice", alice.lastSent())
        assertEquals(alice.pub, leg3.pubkey)
        assertTrue(leg3.challenge.isEmpty())
        assertTrue(bob.transfer.verifyHandshake("alice", leg3))
        assertTrue(bob.transfer.isAuthenticated("alice"))
    }

    @Test
    fun `verifyHandshake rejects a signature over raw SHA256 of the challenge (legacy scheme)`() {
        // Signing oracle guard: a signature over SHA256(challenge as UTF-8) must be rejected, because
        // that is exactly how a Nostr event ID is derived from its NIP-01 serialization.
        val alice = Peer(ALICE_PRIV) // verifier
        val bob = Peer(BOB_PRIV) // signer

        alice.transfer.sendHandshake("bob", alice.pub, emptyList())
        val leg1 = bob.receive("alice", alice.lastSent())

        val legacyHash = MessageDigest.getInstance("SHA-256").digest(leg1.challenge.toByteArray(Charsets.UTF_8))
        val legacySig = Secp256k1.signSchnorr(legacyHash, bob.priv, ByteArray(32)).toHex()
        // Sanity: this is a genuine signature by Bob over the legacy message.
        assertTrue(Secp256k1.verifySchnorr(legacySig.hexToBytes(), legacyHash, bob.pub.hexToBytes()))

        val forged = BleHandshake(bob.pub, emptyList(), challenge = HEX32, challengeResponse = legacySig)
        assertFalse(alice.transfer.verifyHandshake("bob", forged))
        assertFalse(alice.transfer.isAuthenticated("bob"))
    }

    @Test
    fun `handshake signature is not a valid signature over SHA256 of the challenge`() {
        // Domain separation: whatever we sign for a peer must never verify as a plain hash of the
        // peer-supplied challenge, which is the shape of a Nostr event ID.
        val alice = Peer(ALICE_PRIV)
        val bob = Peer(BOB_PRIV)

        alice.transfer.sendHandshake("bob", alice.pub, emptyList())
        val leg1 = bob.receive("alice", alice.lastSent())
        bob.transfer.sendHandshakeResponse("alice", bob.pub, emptyList(), leg1.challenge, leg1.pubkey)
        val leg2 = alice.receive("bob", bob.lastSent())

        val legacyHash = MessageDigest.getInstance("SHA-256").digest(leg1.challenge.toByteArray(Charsets.UTF_8))
        assertFalse(Secp256k1.verifySchnorr(leg2.challengeResponse.hexToBytes(), legacyHash, bob.pub.hexToBytes()))
        val rawHash = MessageDigest.getInstance("SHA-256").digest(leg1.challenge.hexToBytes())
        assertFalse(Secp256k1.verifySchnorr(leg2.challengeResponse.hexToBytes(), rawHash, bob.pub.hexToBytes()))
    }

    @Test
    fun `sendHandshakeResponse refuses to sign a NIP-01-shaped challenge`() {
        transfer.sendHandshakeResponse("ep1", HEX32, emptyList(), NIP01_SHAPED_CHALLENGE, "ab".repeat(32))
        verify(exactly = 0) { nearbySync.sendPayload(any(), any()) }
        verify(exactly = 0) { identity.getPrivateKeyBytes() }
    }

    @Test
    fun `sendChallengeResponse refuses to sign a NIP-01-shaped challenge`() {
        transfer.sendChallengeResponse("ep1", HEX32, NIP01_SHAPED_CHALLENGE, "ab".repeat(32))
        verify(exactly = 0) { nearbySync.sendPayload(any(), any()) }
        verify(exactly = 0) { identity.getPrivateKeyBytes() }
    }

    @Test
    fun `signing refuses challenges that are not exactly 64 lowercase hex chars`() {
        val bad =
            listOf(
                "",
                "ab",
                "cd".repeat(31), // too short
                "cd".repeat(33), // too long
                "CD".repeat(32), // uppercase
                "g".repeat(64), // non-hex
                "cd".repeat(31) + " " // whitespace
            )
        for (challenge in bad) {
            transfer.sendHandshakeResponse("ep1", HEX32, emptyList(), challenge, "ab".repeat(32))
            transfer.sendChallengeResponse("ep1", HEX32, challenge, "ab".repeat(32))
        }
        verify(exactly = 0) { nearbySync.sendPayload(any(), any()) }
        verify(exactly = 0) { identity.getPrivateKeyBytes() }
    }

    @Test
    fun `signing refuses a malformed peer pubkey`() {
        transfer.sendHandshakeResponse("ep1", HEX32, emptyList(), "ab".repeat(32), "not-a-pubkey")
        transfer.sendChallengeResponse("ep1", HEX32, "ab".repeat(32), "AB".repeat(32))
        verify(exactly = 0) { nearbySync.sendPayload(any(), any()) }
        verify(exactly = 0) { identity.getPrivateKeyBytes() }
    }

    @Test
    fun `verifyHandshake rejects a response whose pubkey differs from the one claimed in the initial handshake`() {
        val alice = Peer(ALICE_PRIV) // initiator
        val bob = Peer(BOB_PRIV) // responder
        val mallory = Peer(MALLORY_PRIV) // tries to finish Alice's handshake under a different identity

        alice.transfer.sendHandshake("bob", alice.pub, emptyList())
        val leg1 = bob.receive("alice", alice.lastSent())
        bob.transfer.sendHandshakeResponse("alice", bob.pub, emptyList(), leg1.challenge, leg1.pubkey)
        val leg2 = alice.receive("bob", bob.lastSent())

        // Mallory produces a cryptographically valid leg 3 for Bob's challenge under Mallory's key,
        // delivered on the endpoint that claimed to be Alice.
        mallory.transfer.sendChallengeResponse("bob", mallory.pub, leg2.challenge, bob.pub)
        val leg3 = bob.receive("alice", mallory.lastSent())
        assertEquals(mallory.pub, leg3.pubkey)

        assertFalse(bob.transfer.verifyHandshake("alice", leg3))
        assertFalse(bob.transfer.isAuthenticated("alice"))
    }

    @Test
    fun `verifyHandshake rejects a signature bound to a different verifier`() {
        val alice = Peer(ALICE_PRIV)
        val bob = Peer(BOB_PRIV)
        val carol = Peer(MALLORY_PRIV)

        alice.transfer.sendHandshake("bob", alice.pub, emptyList())
        val leg1 = bob.receive("alice", alice.lastSent())
        // Bob signs Alice's challenge but with Carol as the verifier: a replay to the wrong party.
        bob.transfer.sendHandshakeResponse("alice", bob.pub, emptyList(), leg1.challenge, carol.pub)
        val leg2 = alice.receive("bob", bob.lastSent())

        assertFalse(alice.transfer.verifyHandshake("bob", leg2))
        assertFalse(alice.transfer.isAuthenticated("bob"))
    }

    @Test
    fun `verifyHandshake rejects a malformed pubkey`() {
        val alice = Peer(ALICE_PRIV)
        alice.transfer.sendHandshake("bob", alice.pub, emptyList())
        val hs = BleHandshake("not-hex", emptyList(), challengeResponse = "aa".repeat(64))
        assertFalse(alice.transfer.verifyHandshake("bob", hs))
        assertFalse(alice.transfer.isAuthenticated("bob"))
    }

    @Test
    fun `clearPeer forgets the claimed pubkey so an endpoint can start over`() {
        val alice = Peer(ALICE_PRIV)
        val bob = Peer(BOB_PRIV)
        val carol = Peer(MALLORY_PRIV)

        // Bob records Alice's claim on endpoint "peer", then the peer disconnects.
        alice.transfer.sendHandshake("bob", alice.pub, emptyList())
        val leg1 = bob.receive("peer", alice.lastSent())
        bob.transfer.sendHandshakeResponse("peer", bob.pub, emptyList(), leg1.challenge, leg1.pubkey)
        bob.transfer.clearPeer("peer")

        // Carol reuses the endpoint id and completes a fresh handshake as herself.
        carol.transfer.sendHandshake("bob", carol.pub, emptyList())
        val carolLeg1 = bob.receive("peer", carol.lastSent())
        bob.transfer.sendHandshakeResponse("peer", bob.pub, emptyList(), carolLeg1.challenge, carolLeg1.pubkey)
        val leg2 = carol.receive("bob", bob.lastSent())
        assertTrue(carol.transfer.verifyHandshake("bob", leg2))
        carol.transfer.sendChallengeResponse("bob", carol.pub, leg2.challenge, leg2.pubkey)
        val leg3 = bob.receive("peer", carol.lastSent())
        assertTrue(bob.transfer.verifyHandshake("peer", leg3))
        assertTrue(bob.transfer.isAuthenticated("peer"))
    }

    // --- Auth-gated operations ---

    @Test
    fun `sendGroupIds refuses unauthenticated peer`() {
        transfer.sendGroupIds("ep1", listOf("g1"))
        verify(exactly = 0) { nearbySync.sendPayload("ep1", match { it[0] == BleTransfer.MSG_GROUP_IDS }) }
    }

    @Test
    fun `sendSyncRequest refuses unauthenticated peer`() {
        transfer.sendSyncRequest("ep1", "g1", listOf("e1"))
        verify(exactly = 0) { nearbySync.sendPayload("ep1", match { it[0] == BleTransfer.MSG_SYNC_REQ }) }
    }

    @Test
    fun `sendMissingEvents refuses unauthenticated peer`() = runBlocking {
        transfer.sendMissingEvents("ep1", "g1", emptySet())
        verify(exactly = 0) { nearbySync.sendPayload(any(), any()) }
    }

    @Test
    fun `sendMissingEventsBinary refuses unauthenticated peer`() = runBlocking {
        transfer.sendMissingEventsBinary("ep1", "g1", emptySet(), "pub")
        verify(exactly = 0) { nearbySync.sendPayload(any(), any()) }
    }

    @Test
    fun `processBinaryPayload rejects unauthenticated peer`() = runBlocking {
        val result = transfer.processBinaryPayload("ep1", byteArrayOf(1, 2, 3))
        assertNull(result)
    }

    // --- Forwarding stored events to an authenticated peer ---

    /** Run the full three-leg handshake so [alice] has authenticated [bob] on endpoint "bob". */
    private fun authenticate(alice: Peer, bob: Peer) {
        alice.transfer.sendHandshake("bob", alice.pub, emptyList())
        val leg1 = bob.receive("alice", alice.lastSent())
        bob.transfer.sendHandshakeResponse("alice", bob.pub, emptyList(), leg1.challenge, leg1.pubkey)
        val leg2 = alice.receive("bob", bob.lastSent())
        assertTrue(alice.transfer.verifyHandshake("bob", leg2))
    }

    private fun stored(
        id: String,
        author: String,
        sig: String,
        eventType: String = "expense",
        json: String? = "{\"id\":\"$id\"}"
    ) = EventEntity(
        eventId = id, groupId = "g1", pubkey = author, createdAt = 1000, kind = 30078,
        contentEncrypted = "enc", eventType = eventType, expenseUuid = null, sig = sig, receivedAt = 1000,
        originalEventJson = json
    )

    private fun eventPayloads(peer: Peer): List<String> =
        peer.sent.filter { it.isNotEmpty() && it[0] == BleTransfer.MSG_EVENT }
            .map { String(it.copyOfRange(1, it.size)) }

    @Test
    fun `sendMissingEvents forwards only third-party-verifiable events`() = runBlocking {
        val alice = Peer(ALICE_PRIV)
        val bob = Peer(BOB_PRIV)
        authenticate(alice, bob)
        coEvery { alice.groupRepo.getById("g1") } returns
            Group("g1", "Test", "", alice.pub, 1000, listOf(alice.pub, bob.pub), emptyList())
        coEvery { alice.eventDao.getEventsByGroup("g1") } returns
            listOf(
                stored("signed", alice.pub, sig = "ab".repeat(64)),
                // Received as a NIP-59 rumor: unsigned, authenticated only by a seal we verified.
                stored("sealed", "cc".repeat(32), sig = EventSnapshot.SEAL_SIG_PREFIX + "cd".repeat(64)),
                stored("unsigned", "cc".repeat(32), sig = ""),
                stored("nojson", alice.pub, sig = "ab".repeat(64), json = null)
            )

        alice.transfer.sendMissingEvents("bob", "g1", peerEventIds = emptySet())

        assertEquals(listOf("""{"id":"signed"}"""), eventPayloads(alice))
    }

    @Test
    fun `sendMissingEvents still skips events the peer already has`() = runBlocking {
        val alice = Peer(ALICE_PRIV)
        val bob = Peer(BOB_PRIV)
        authenticate(alice, bob)
        coEvery { alice.groupRepo.getById("g1") } returns
            Group("g1", "Test", "", alice.pub, 1000, listOf(alice.pub, bob.pub), emptyList())
        coEvery { alice.eventDao.getEventsByGroup("g1") } returns
            listOf(stored("known", alice.pub, sig = "ab".repeat(64)), stored("new", alice.pub, sig = "ab".repeat(64)))

        alice.transfer.sendMissingEvents("bob", "g1", peerEventIds = setOf("known"))

        assertEquals(listOf("""{"id":"new"}"""), eventPayloads(alice))
    }

    @Test
    fun `sendMissingEventsBinary forwards only third-party-verifiable events`() = runBlocking {
        val alice = Peer(ALICE_PRIV)
        val bob = Peer(BOB_PRIV)
        authenticate(alice, bob)
        coEvery { alice.groupRepo.getById("g1") } returns
            Group("g1", "Test", "", alice.pub, 1000, listOf(alice.pub, bob.pub), emptyList())
        coEvery { alice.eventDao.getEventsByGroup("g1") } returns
            listOf(
                stored("signed", alice.pub, sig = "ab".repeat(64)),
                stored("sealed", "cc".repeat(32), sig = EventSnapshot.SEAL_SIG_PREFIX + "cd".repeat(64)),
                stored("unsigned", "cc".repeat(32), sig = "")
            )
        val handshakeFrames = alice.sent.size

        alice.transfer.sendMissingEventsBinary("bob", "g1", peerEventIds = emptySet(), senderPubkey = alice.pub)

        val frames = alice.sent.drop(handshakeFrames)
        // One event, small enough for a single unfragmented packet.
        assertEquals(1, frames.size)
        val packet = BleProtocol.decode(frames.single())
        assertEquals("""{"id":"signed"}""", String(packet!!.payload))
    }

    // --- processPayload ---

    @Test
    fun `processPayload returns null for empty data`() = runBlocking {
        assertNull(transfer.processPayload("ep1", byteArrayOf()))
    }

    @Test
    fun `processPayload parses handshake`() = runBlocking {
        val hs = BleHandshake("pub", listOf("g1"))
        val body = json.encodeToString(BleHandshake.serializer(), hs).toByteArray()
        val data = byteArrayOf(BleTransfer.MSG_HANDSHAKE) + body
        val result = transfer.processPayload("ep1", data)
        assertTrue(result is BleHandshake)
        assertEquals("pub", (result as BleHandshake).pubkey)
    }

    @Test
    fun `processPayload rejects event from unauthenticated peer`() = runBlocking {
        val data = byteArrayOf(BleTransfer.MSG_EVENT) + "{}".toByteArray()
        val result = transfer.processPayload("ep1", data)
        assertNull(result)
    }

    @Test
    fun `processPayload rejects group IDs from unauthenticated peer`() = runBlocking {
        val data = byteArrayOf(BleTransfer.MSG_GROUP_IDS) + """["g1"]""".toByteArray()
        val result = transfer.processPayload("ep1", data)
        assertNull(result)
    }

    @Test
    fun `processPayload returns null for unknown type`() = runBlocking {
        val result = transfer.processPayload("ep1", byteArrayOf(0x7F, 0x01))
        assertNull(result)
    }

    // --- Data classes ---

    @Test
    fun `BleHandshake serialization`() {
        val hs = BleHandshake("pub", listOf("g1"), "chal", "resp")
        val s = json.encodeToString(BleHandshake.serializer(), hs)
        val d = json.decodeFromString<BleHandshake>(s)
        assertEquals(hs, d)
    }

    @Test
    fun `BleHandshake defaults`() {
        val hs = BleHandshake("pub", emptyList())
        assertEquals("", hs.challenge)
        assertEquals("", hs.challengeResponse)
    }

    @Test
    fun `BleSyncRequest serialization`() {
        val req = BleSyncRequest("g1", listOf("e1", "e2"))
        val s = json.encodeToString(BleSyncRequest.serializer(), req)
        val d = json.decodeFromString<BleSyncRequest>(s)
        assertEquals(req, d)
    }

    @Test
    fun `HANDSHAKE_TIMEOUT_MS is 10 seconds`() {
        assertEquals(10_000L, BleTransfer.HANDSHAKE_TIMEOUT_MS)
    }
}
