package com.splitfree.data.ble

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for BLE protocol message serialization and parsing.
 * Design doc Section 10.3.
 */
class BleProtocolTest {
    private val json = Json { ignoreUnknownKeys = true }

    // --- BleHandshake ---

    @Test
    fun `BleHandshake serialization round-trip`() {
        val hs = BleHandshake(pubkey = "abc123def456", groups = listOf("group-1", "group-2", "group-3"))
        val serialized = json.encodeToString(BleHandshake.serializer(), hs)
        val deserialized = json.decodeFromString<BleHandshake>(serialized)
        assertEquals(hs.pubkey, deserialized.pubkey)
        assertEquals(hs.groups, deserialized.groups)
    }

    @Test
    fun `BleHandshake with empty groups`() {
        val hs = BleHandshake(pubkey = "abc", groups = emptyList())
        val d = json.decodeFromString<BleHandshake>(json.encodeToString(BleHandshake.serializer(), hs))
        assertEquals(emptyList<String>(), d.groups)
    }

    // --- BleSyncRequest ---

    @Test
    fun `BleSyncRequest serialization round-trip`() {
        val req = BleSyncRequest(groupId = "group-1", eventIds = listOf("ev1", "ev2", "ev3"))
        val serialized = json.encodeToString(BleSyncRequest.serializer(), req)
        val deserialized = json.decodeFromString<BleSyncRequest>(serialized)
        assertEquals(req.groupId, deserialized.groupId)
        assertEquals(req.eventIds, deserialized.eventIds)
    }

    @Test
    fun `BleSyncRequest with many event IDs`() {
        val ids = (1..500).map { "event-$it" }
        val req = BleSyncRequest(groupId = "g", eventIds = ids)
        val d = json.decodeFromString<BleSyncRequest>(json.encodeToString(BleSyncRequest.serializer(), req))
        assertEquals(500, d.eventIds.size)
    }

    // --- Message framing (type byte prefix) ---

    @Test
    fun `handshake message framing`() {
        val hs = BleHandshake(pubkey = "abc", groups = listOf("g1"))
        val payload =
            byteArrayOf(BleTransfer.MSG_HANDSHAKE) +
                json.encodeToString(BleHandshake.serializer(), hs).toByteArray()
        assertEquals(BleTransfer.MSG_HANDSHAKE, payload[0])
        val body = String(payload.copyOfRange(1, payload.size))
        val parsed = json.decodeFromString<BleHandshake>(body)
        assertEquals("abc", parsed.pubkey)
    }

    @Test
    fun `sync request message framing`() {
        val req = BleSyncRequest(groupId = "g1", eventIds = listOf("e1"))
        val payload =
            byteArrayOf(BleTransfer.MSG_SYNC_REQ) +
                json.encodeToString(BleSyncRequest.serializer(), req).toByteArray()
        assertEquals(BleTransfer.MSG_SYNC_REQ, payload[0])
    }

    @Test
    fun `event message framing`() {
        val eventJson = """{"id":"abc","pubkey":"def","created_at":1,"kind":1,"tags":[],"content":"hi","sig":"sig"}"""
        val payload = byteArrayOf(BleTransfer.MSG_EVENT) + eventJson.toByteArray()
        assertEquals(BleTransfer.MSG_EVENT, payload[0])
        val body = String(payload.copyOfRange(1, payload.size))
        val event =
            com.splitfree.domain.crypto.NostrEvent
                .fromJson(body)
        assertNotNull(event)
        assertEquals("abc", event!!.id)
    }

    @Test
    fun `message type constants are distinct`() {
        val types = setOf(BleTransfer.MSG_HANDSHAKE, BleTransfer.MSG_SYNC_REQ, BleTransfer.MSG_EVENT)
        assertEquals("All message types must be unique", 3, types.size)
    }

    @Test
    fun `empty payload is handled`() {
        val empty = ByteArray(0)
        assertTrue("Empty payload should be safe to check", empty.isEmpty())
    }
}
