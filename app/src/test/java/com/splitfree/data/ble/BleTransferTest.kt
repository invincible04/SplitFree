package com.splitfree.data.ble

import com.splitfree.data.local.dao.EventDao
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.IdentityManager
import com.splitfree.sync.event.EventProcessor
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
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
