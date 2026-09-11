package com.splitfree.data.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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
        // Last fragment should complete reassembly (already consumed above)
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

    // --- FragmentManager hardening ---

    /** Build a raw fragment with an explicit header; the receive side does not bound chunk size by MTU. */
    private fun rawFragment(msb: Long, lsb: Long, index: Int, total: Int, chunk: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(20 + chunk.size).order(ByteOrder.BIG_ENDIAN)
        buf.putLong(msb)
        buf.putLong(lsb)
        buf.putShort(index.toShort())
        buf.putShort(total.toShort())
        buf.put(chunk)
        return buf.array()
    }

    private fun add(peer: String, msb: Long, lsb: Long, index: Int, total: Int, vararg chunk: Byte): ByteArray? =
        FragmentManager.addFragment(peer, rawFragment(msb, lsb, index, total, chunk))

    @Test
    fun `oversized message is rejected as soon as the running total exceeds the bound`() {
        // 100 KB buffered, then a 40 KB chunk would push past 128 KB: rejected on arrival.
        assertNull(FragmentManager.addFragment("peer", rawFragment(7, 7, 0, 3, ByteArray(100_000) { 1 })))
        assertNull(FragmentManager.addFragment("peer", rawFragment(7, 7, 1, 3, ByteArray(40_000) { 2 })))

        // The key was discarded: completing the "same" message with small chunks yields only the
        // small chunks, proving the 100 KB piece is no longer buffered.
        assertNull(add("peer", 7, 7, 1, 3, 9))
        assertNull(add("peer", 7, 7, 2, 3, 8))
        assertArrayEquals(byteArrayOf(7, 9, 8), add("peer", 7, 7, 0, 3, 7))
    }

    @Test
    fun `message exactly at the size bound is still accepted`() {
        val half = 131_072 / 2
        assertNull(FragmentManager.addFragment("peer", rawFragment(8, 8, 0, 2, ByteArray(half) { 1 })))
        val assembled = FragmentManager.addFragment("peer", rawFragment(8, 8, 1, 2, ByteArray(half) { 2 }))
        assertNotNull(assembled)
        assertEquals(131_072, assembled!!.size)
    }

    @Test
    fun `a retransmitted fragment replaces the earlier copy instead of double-counting its size`() {
        val big = 131_072 / 2 + 100
        // First copy of index 0 is large; a retransmit of index 0 must not be summed on top of it.
        assertNull(FragmentManager.addFragment("peer", rawFragment(9, 9, 0, 2, ByteArray(big) { 1 })))
        assertNull(FragmentManager.addFragment("peer", rawFragment(9, 9, 0, 2, ByteArray(big) { 1 })))
        val assembled = FragmentManager.addFragment("peer", rawFragment(9, 9, 1, 2, ByteArray(10) { 2 }))
        assertNotNull(assembled)
        assertEquals(big + 10, assembled!!.size)
    }

    @Test
    fun `fragment index outside total is rejected without occupying the slot`() {
        assertNull(add("peer", 1, 1, 2, 2, 0x55))
        assertNull(add("peer", 1, 1, 500, 2, 0x55))

        // With the bogus index ignored, the two real fragments complete the message. Under the
        // old code the stray entry made frags.size never equal total.
        assertNull(add("peer", 1, 1, 0, 2, 1))
        assertArrayEquals(byteArrayOf(1, 2), add("peer", 1, 1, 1, 2, 2))
    }

    @Test
    fun `pending reassemblies are strictly bounded and the oldest is evicted first`() {
        // Start MAX_PENDING (20) messages from distinct ids, each missing its second half.
        for (i in 1..20) {
            assertNull(add("peer", i.toLong(), 0, 0, 2, i.toByte()))
        }
        // The 21st new message forces eviction of the oldest (id 1), even though nothing is stale.
        assertNull(add("peer", 21, 0, 0, 2, 21))

        // id 2 and id 21 were kept and complete normally.
        assertArrayEquals(byteArrayOf(2, 102), add("peer", 2, 0, 1, 2, 102))
        assertArrayEquals(byteArrayOf(21, 121), add("peer", 21, 0, 1, 2, 121))
        // id 1 lost its first half: its second half alone cannot complete it.
        assertNull(add("peer", 1, 0, 1, 2, 101))
    }

    @Test
    fun `eviction is per message id not per peer`() {
        for (i in 1..20) {
            assertNull(add("peer-$i", 5, 5, 0, 2, i.toByte()))
        }
        assertNull(add("peer-21", 5, 5, 0, 2, 21))

        assertArrayEquals(byteArrayOf(21, 0), add("peer-21", 5, 5, 1, 2, 0))
        assertNull(add("peer-1", 5, 5, 1, 2, 0))
    }

    @Test
    fun `a fragment that changes the total mid-message discards the message`() {
        assertNull(add("peer", 3, 3, 0, 3, 1))
        assertNull(add("peer", 3, 3, 1, 2, 2)) // total 3 -> 2
        // Nothing left buffered for this id: a fresh 2-part message completes with just its own parts.
        assertNull(add("peer", 3, 3, 0, 2, 7))
        assertArrayEquals(byteArrayOf(7, 8), add("peer", 3, 3, 1, 2, 8))
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
