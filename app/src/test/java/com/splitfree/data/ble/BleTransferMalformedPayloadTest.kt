package com.splitfree.data.ble

import com.splitfree.data.identity.IdentityManager
import com.splitfree.data.local.dao.EventDao
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.sync.event.EventProcessor
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * A BLE peer is untrusted: MSG_HANDSHAKE is parsed before authentication, and the caller
 * collects [BleTransfer.processPayload] inside a flow whose collector dies with any throw.
 * Every malformed frame must therefore come back as null rather than an exception.
 */
class BleTransferMalformedPayloadTest {
    private val eventDao = mockk<EventDao>(relaxed = true)
    private val groupRepo = mockk<GroupRepository>(relaxed = true)
    private val signer = mockk<EventSigner>()
    private val nearbySync = mockk<NearbySync>(relaxed = true)
    private val identity = mockk<IdentityManager>()
    private val eventProcessor = mockk<EventProcessor>(relaxed = true)

    private lateinit var transfer: BleTransfer

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

    private fun frame(type: Byte, body: String) = byteArrayOf(type) + body.toByteArray()

    @Test
    fun `handshake with truncated JSON returns null`() = runBlocking {
        assertNull(transfer.processPayload("ep1", frame(BleTransfer.MSG_HANDSHAKE, "{")))
        assertFalse(transfer.isAuthenticated("ep1"))
    }

    @Test
    fun `handshake with non-JSON bytes returns null`() = runBlocking {
        assertNull(transfer.processPayload("ep1", byteArrayOf(BleTransfer.MSG_HANDSHAKE, 0x00, 0xFF.toByte(), 0x7F)))
    }

    @Test
    fun `handshake with empty body returns null`() = runBlocking {
        assertNull(transfer.processPayload("ep1", byteArrayOf(BleTransfer.MSG_HANDSHAKE)))
    }

    @Test
    fun `handshake missing required pubkey field returns null`() = runBlocking {
        assertNull(transfer.processPayload("ep1", frame(BleTransfer.MSG_HANDSHAKE, """{"groups":[]}""")))
    }

    @Test
    fun `handshake with wrong field type returns null`() = runBlocking {
        assertNull(transfer.processPayload("ep1", frame(BleTransfer.MSG_HANDSHAKE, """{"pubkey":42,"groups":[]}""")))
    }

    @Test
    fun `handshake with JSON array instead of object returns null`() = runBlocking {
        assertNull(transfer.processPayload("ep1", frame(BleTransfer.MSG_HANDSHAKE, """["not","an","object"]""")))
    }

    @Test
    fun `sync request with malformed JSON returns null and sends nothing`() = runBlocking {
        authenticate("ep1")
        assertNull(transfer.processPayload("ep1", frame(BleTransfer.MSG_SYNC_REQ, """{"groupId":}""")))
        io.mockk.verify(exactly = 0) { nearbySync.sendPayload(any(), any()) }
    }

    @Test
    fun `group IDs with malformed JSON returns null for authenticated peer`() = runBlocking {
        authenticate("ep1")
        assertNull(transfer.processPayload("ep1", frame(BleTransfer.MSG_GROUP_IDS, """["g1",""")))
    }

    @Test
    fun `group IDs with wrong element type returns null for authenticated peer`() = runBlocking {
        authenticate("ep1")
        assertNull(transfer.processPayload("ep1", frame(BleTransfer.MSG_GROUP_IDS, """[{"g":1}]""")))
    }

    @Test
    fun `event with malformed JSON returns false for authenticated peer`() = runBlocking {
        authenticate("ep1")
        val result = transfer.processPayload("ep1", frame(BleTransfer.MSG_EVENT, "}{"))
        org.junit.Assert.assertEquals(false, result)
    }

    /** Mark a peer authenticated without a real Schnorr exchange. */
    private fun authenticate(endpointId: String) {
        val field = BleTransfer::class.java.getDeclaredField("authenticatedPeers")
        field.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        (field.get(transfer) as MutableMap<String, String>)[endpointId] = "peer-pubkey"
    }
}
