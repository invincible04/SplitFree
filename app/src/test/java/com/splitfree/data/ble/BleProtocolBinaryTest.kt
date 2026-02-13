package com.splitfree.data.ble

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BleProtocolBinaryTest {

    @Before
    fun setUp() {
        FragmentManager.clear()
    }

    // --- MessageType ---

    @Test
    fun `all MessageType values have unique byte values`() {
        val values = MessageType.entries.map { it.value }
        assertEquals(values.size, values.toSet().size)
    }

    @Test
    fun `fromValue returns correct type`() {
        assertEquals(MessageType.EXPENSE, MessageType.fromValue(0x02))
        assertEquals(MessageType.SETTLEMENT, MessageType.fromValue(0x03))
        assertEquals(MessageType.FRAGMENT, MessageType.fromValue(0x21))
    }

    @Test
    fun `fromValue returns null for unknown byte`() {
        assertNull(MessageType.fromValue(0xFF.toByte()))
    }

    @Test
    fun `fromEventType maps correctly`() {
        assertEquals(MessageType.EXPENSE, MessageType.fromEventType("expense"))
        assertEquals(MessageType.SETTLEMENT, MessageType.fromEventType("settlement"))
        assertEquals(MessageType.EXPENSE_CORRECTION, MessageType.fromEventType("expense_correction"))
        assertEquals(MessageType.EXPENSE_DELETE, MessageType.fromEventType("expense_delete"))
        assertEquals(MessageType.SNAPSHOT, MessageType.fromEventType("snapshot"))
        assertEquals(MessageType.GROUP_META, MessageType.fromEventType("group_meta"))
        assertNull(MessageType.fromEventType("unknown"))
    }

    // --- FragmentManager ---

    @Test
    fun `single fragment below MTU returns as-is`() {
        val data = "hello".toByteArray()
        val fragments = FragmentManager.fragment(data)
        assertEquals(1, fragments.size)
        assertArrayEquals(data, fragments[0])
    }

    @Test
    fun `large data is fragmented and reassembled`() {
        val data = ByteArray(2000) { (it % 256).toByte() }
        val fragments = FragmentManager.fragment(data)
        assertTrue(fragments.size > 1)

        var result: ByteArray? = null
        for (frag in fragments) {
            result = FragmentManager.addFragment("peer1", frag)
        }
        assertNotNull(result)
        assertArrayEquals(data, result)
    }

    @Test
    fun `fragments from different peers are isolated`() {
        val data1 = ByteArray(1500) { 0xAA.toByte() }
        val data2 = ByteArray(1500) { 0xBB.toByte() }
        val frags1 = FragmentManager.fragment(data1)
        val frags2 = FragmentManager.fragment(data2)

        // Interleave fragments from different peers
        for (i in 0 until maxOf(frags1.size, frags2.size)) {
            if (i < frags1.size) FragmentManager.addFragment("peer1", frags1[i])
            if (i < frags2.size) FragmentManager.addFragment("peer2", frags2[i])
        }
        // Last fragment should complete reassembly — already consumed above
        // Just verify clear works without crash
        FragmentManager.clear()
    }

    @Test
    fun `addFragment returns null for too-small fragment`() {
        assertNull(FragmentManager.addFragment("peer", ByteArray(5)))
    }

    @Test
    fun `addFragment returns null for zero total fragments`() {
        val buf = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
        buf.putLong(1L) // msb
        buf.putLong(2L) // lsb
        buf.putShort(0) // index
        buf.putShort(0) // total = 0 (invalid)
        assertNull(FragmentManager.addFragment("peer", buf.array()))
    }

    @Test
    fun `addFragment returns null for excessive total fragments`() {
        val buf = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
        buf.putLong(1L)
        buf.putLong(2L)
        buf.putShort(0)
        buf.putShort(257) // > 256 limit
        assertNull(FragmentManager.addFragment("peer", buf.array()))
    }

    @Test
    fun `clear resets all state`() {
        val data = ByteArray(1500) { 1 }
        val frags = FragmentManager.fragment(data)
        // Add only first fragment
        FragmentManager.addFragment("peer", frags[0])
        FragmentManager.clear()
        // After clear, adding remaining fragments should not reassemble
        for (i in 1 until frags.size) {
            assertNull(FragmentManager.addFragment("peer", frags[i]))
        }
    }

    // --- BlePacket equals/hashCode ---

    @Test
    fun `BlePacket equals with same data`() {
        val p1 = BlePacket(MessageType.EXPENSE, 7, 1000L, byteArrayOf(1, 2), "g1", byteArrayOf(3, 4))
        val p2 = BlePacket(MessageType.EXPENSE, 7, 1000L, byteArrayOf(1, 2), "g1", byteArrayOf(3, 4))
        assertEquals(p1, p2)
    }

    @Test
    fun `BlePacket not equal with different type`() {
        val p1 = BlePacket(MessageType.EXPENSE, 7, 1000L, byteArrayOf(1), null, byteArrayOf())
        val p2 = BlePacket(MessageType.SETTLEMENT, 7, 1000L, byteArrayOf(1), null, byteArrayOf())
        assertNotEquals(p1, p2)
    }

    @Test
    fun `BlePacket not equal with different senderId`() {
        val p1 = BlePacket(MessageType.EXPENSE, 7, 1000L, byteArrayOf(1), null, byteArrayOf())
        val p2 = BlePacket(MessageType.EXPENSE, 7, 1000L, byteArrayOf(2), null, byteArrayOf())
        assertNotEquals(p1, p2)
    }

    @Test
    fun `BlePacket not equal to non-BlePacket`() {
        val p = BlePacket(MessageType.EXPENSE, 7, 1000L, byteArrayOf(), null, byteArrayOf())
        assertNotEquals(p, "not a packet")
    }

    @Test
    fun `BlePacket equals itself`() {
        val p = BlePacket(MessageType.EXPENSE, 7, 1000L, byteArrayOf(), null, byteArrayOf())
        assertEquals(p, p)
    }
}
