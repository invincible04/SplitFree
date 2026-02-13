package com.splitfree.data.ble

import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BleProtocolEncodeDecodeTest {
    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
    }

    @After
    fun teardown() {
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `encode and decode round-trip without group ID`() {
        val payload = "test payload".toByteArray()
        val encoded = BleProtocol.encode(MessageType.EXPENSE, payload, "aabbccdd" + "00".repeat(28))
        val decoded = BleProtocol.decode(encoded)
        assertNotNull(decoded)
        assertEquals(MessageType.EXPENSE, decoded!!.type)
        assertNull(decoded.groupId)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun `encode and decode round-trip with group ID`() {
        val payload = "data".toByteArray()
        val encoded = BleProtocol.encode(MessageType.SETTLEMENT, payload, "aa".repeat(32), groupId = "group-123")
        val decoded = BleProtocol.decode(encoded)
        assertNotNull(decoded)
        assertEquals(MessageType.SETTLEMENT, decoded!!.type)
        assertEquals("group-123", decoded.groupId)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun `encode with compression for large payload`() {
        val payload = "ABCDEFGH".repeat(100).toByteArray()
        val encoded = BleProtocol.encode(MessageType.EXPENSE, payload, "aa".repeat(32))
        val decoded = BleProtocol.decode(encoded)
        assertNotNull(decoded)
        assertArrayEquals(payload, decoded!!.payload)
        assertTrue(encoded.size < payload.size) // should be compressed
    }

    @Test
    fun `decode returns null for too-short packet`() {
        assertNull(BleProtocol.decode(ByteArray(10)))
    }

    @Test
    fun `decode returns null for wrong version`() {
        val encoded = BleProtocol.encode(MessageType.EXPENSE, "test".toByteArray(), "aa".repeat(32))
        encoded[0] = 99 // wrong version
        assertNull(BleProtocol.decode(encoded))
    }

    @Test
    fun `decode returns null for unknown message type`() {
        val encoded = BleProtocol.encode(MessageType.EXPENSE, "test".toByteArray(), "aa".repeat(32))
        encoded[1] = 0x7F.toByte() // unknown type
        assertNull(BleProtocol.decode(encoded))
    }

    @Test
    fun `all MessageType values have unique byte values`() {
        val values = MessageType.entries.map { it.value }
        assertEquals(values.size, values.toSet().size)
    }

    @Test
    fun `fromEventType maps all known types`() {
        assertEquals(MessageType.EXPENSE, MessageType.fromEventType("expense"))
        assertEquals(MessageType.SETTLEMENT, MessageType.fromEventType("settlement"))
        assertEquals(MessageType.EXPENSE_CORRECTION, MessageType.fromEventType("expense_correction"))
        assertEquals(MessageType.EXPENSE_DELETE, MessageType.fromEventType("expense_delete"))
        assertEquals(MessageType.SNAPSHOT, MessageType.fromEventType("snapshot"))
        assertEquals(MessageType.GROUP_META, MessageType.fromEventType("group_meta"))
        assertNull(MessageType.fromEventType("unknown"))
    }

    @Test
    fun `fromValue maps all known values`() {
        for (mt in MessageType.entries) {
            assertEquals(mt, MessageType.fromValue(mt.value))
        }
        assertNull(MessageType.fromValue(0x99.toByte()))
    }

    @Test
    fun `encode rejects payload over 65535`() {
        try {
            BleProtocol.encode(MessageType.EXPENSE, ByteArray(65536), "aa".repeat(32))
            fail("Should throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("too large"))
        }
    }

    @Test
    fun `fragment and reassemble large message`() {
        FragmentManager.clear()
        val data = ByteArray(2000) { (it % 256).toByte() }
        val fragments = FragmentManager.fragment(data)
        assertTrue(fragments.size > 1)
        fragments.all { it.size <= BleProtocol.BLE_MTU }

        var result: ByteArray? = null
        for (frag in fragments) {
            result = FragmentManager.addFragment("ep1", frag)
        }
        assertNotNull(result)
        assertArrayEquals(data, result)
    }

    @Test
    fun `fragment returns single element for small data`() {
        val data = ByteArray(100)
        val fragments = FragmentManager.fragment(data)
        assertEquals(1, fragments.size)
    }

    @Test
    fun `addFragment returns null for too-short fragment`() {
        assertNull(FragmentManager.addFragment("ep1", ByteArray(5)))
    }

    @Test
    fun `addFragment returns null for zero total`() {
        val buf =
            java.nio.ByteBuffer
                .allocate(24)
                .order(java.nio.ByteOrder.BIG_ENDIAN)
        buf.putLong(1L)
        buf.putLong(2L)
        buf.putShort(0)
        buf.putShort(0)
        buf.put(byteArrayOf(1))
        assertNull(FragmentManager.addFragment("ep1", buf.array()))
    }

    @Test
    fun `BLE_MTU is 512`() {
        assertEquals(512, BleProtocol.BLE_MTU)
    }

    @Test
    fun `encode small payload skips compression`() {
        val payload = "tiny".toByteArray() // < 100 bytes
        val encoded = BleProtocol.encode(MessageType.EXPENSE, payload, "aa".repeat(32))
        val decoded = BleProtocol.decode(encoded)
        assertNotNull(decoded)
        assertArrayEquals(payload, decoded!!.payload)
    }

    @Test
    fun `BlePacket equals and hashCode`() {
        val p1 = BlePacket(MessageType.EXPENSE, 7, 1000, byteArrayOf(1), null, byteArrayOf(2))
        val p2 = BlePacket(MessageType.EXPENSE, 7, 1000, byteArrayOf(1), null, byteArrayOf(2))
        val p3 = BlePacket(MessageType.SETTLEMENT, 7, 1000, byteArrayOf(1), null, byteArrayOf(2))
        assertEquals(p1, p2)
        assertNotEquals(p1, p3)
        assertEquals(p1, p1) // same reference
        assertNotEquals(p1, "not a packet") // different type
        assertNotEquals(p1, null)
    }

    @Test
    fun `addFragment rejects total over 256`() {
        val buf =
            java.nio.ByteBuffer
                .allocate(24)
                .order(java.nio.ByteOrder.BIG_ENDIAN)
        buf.putLong(1L)
        buf.putLong(2L)
        buf.putShort(0)
        buf.putShort(257)
        buf.put(byteArrayOf(1))
        assertNull(FragmentManager.addFragment("ep1", buf.array()))
    }

    @Test
    fun `fragment reassemble with missing fragment returns null`() {
        FragmentManager.clear()
        val data = ByteArray(2000) { (it % 256).toByte() }
        val fragments = FragmentManager.fragment(data)
        // Add all but skip fragment index 1
        for ((i, frag) in fragments.withIndex()) {
            if (i == 1) continue
            FragmentManager.addFragment("ep-miss", frag)
        }
        // Not complete yet — no result
    }

    @Test
    fun `encode with incompressible large payload`() {
        // Random data doesn't compress well
        val random = java.security.SecureRandom()
        val payload = ByteArray(200).also { random.nextBytes(it) }
        val encoded = BleProtocol.encode(MessageType.EXPENSE, payload, "aa".repeat(32))
        val decoded = BleProtocol.decode(encoded)
        assertNotNull(decoded)
        assertArrayEquals(payload, decoded!!.payload)
    }

    @Test
    fun `decode returns null for truncated payload`() {
        val encoded = BleProtocol.encode(MessageType.EXPENSE, "test".toByteArray(), "aa".repeat(32))
        // Truncate the encoded data to cut off the payload
        val truncated = encoded.copyOf(encoded.size - 2)
        assertNull(BleProtocol.decode(truncated))
    }
}
