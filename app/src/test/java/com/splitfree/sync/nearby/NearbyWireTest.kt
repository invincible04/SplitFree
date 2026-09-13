package com.splitfree.sync.nearby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyWireTest {
    private fun hexId(i: Int): String = "%064x".format(i)

    @Test
    fun `every message round-trips through encode and decode`() {
        val messages: List<NearbyMessage> =
            listOf(
                Hello(2, hexId(1), hexId(2), incoming = true, caps = listOf("a")),
                Auth("ab".repeat(64)),
                OpenGroup("g", joinEvent = "{}"),
                OpenGroupResult("g", ok = false, reason = "unauthorized"),
                InventoryPage(1, 0, last = true, delta = false, items = listOf(InventoryItem(hexId(3), "e"))),
                Want(1, listOf(hexId(3))),
                Record(1, hexId(3), "e", 0, 1, "{}"),
                Result(1, hexId(3), RecordOutcome.CARRIED),
                ReconcileResult(1, applied = 2, alreadyApplied = 1, unresolved = 3),
                Close("stopped")
            )
        for (m in messages) {
            val decoded = NearbyWire.decode(NearbyWire.encode(m))
            assertTrue(decoded is NearbyWire.Decoded.Message)
            assertEquals(m, (decoded as NearbyWire.Decoded.Message).message)
        }
    }

    @Test
    fun `legacy discriminators are recognised as an incompatible peer and garbage is invalid`() {
        for (b in 1..4) assertEquals(NearbyWire.Decoded.LegacyPeer, NearbyWire.decode(byteArrayOf(b.toByte(), 0x7b)))
        assertEquals(NearbyWire.Decoded.Invalid, NearbyWire.decode(byteArrayOf()))
        assertEquals(NearbyWire.Decoded.Invalid, NearbyWire.decode(byteArrayOf(0x55)))
        assertEquals(
            NearbyWire.Decoded.Invalid,
            NearbyWire.decode(
                byteArrayOf(NearbyWire.FRAME_MAGIC, 0x42) + "{}".toByteArray()
            )
        )
        assertEquals(
            NearbyWire.Decoded.Invalid,
            NearbyWire.decode(
                byteArrayOf(NearbyWire.FRAME_MAGIC, NearbyWire.TYPE_WANT) + "nope".toByteArray()
            )
        )
        val oversized = ByteArray(NearbyWire.MAX_FRAME_BYTES + 1) { 0x20 }.also {
            it[0] = NearbyWire.FRAME_MAGIC
            it[1] =
                NearbyWire.TYPE_WANT
        }
        assertEquals(NearbyWire.Decoded.Invalid, NearbyWire.decode(oversized))
    }

    @Test
    fun `pagination respects item and byte limits and marks the last page`() {
        val items = (0 until 20_000).map { InventoryItem(hexId(it), "e") }
        val pages = NearbyWire.paginate(1, delta = false, items)
        assertTrue(pages.size >= 20_000 / NearbyWire.MAX_INVENTORY_PAGE_ITEMS)
        assertEquals(items.size, pages.sumOf { it.items.size })
        pages.forEachIndexed { i, p ->
            assertEquals(i, p.page)
            assertEquals(i == pages.lastIndex, p.last)
            assertTrue(p.items.size <= NearbyWire.MAX_INVENTORY_PAGE_ITEMS)
            assertTrue(NearbyWire.encode(p).size <= NearbyWire.MAX_FRAME_BYTES)
        }
        assertEquals(items, pages.flatMap { it.items })
    }

    @Test
    fun `an empty inventory still produces one terminal page`() {
        val pages = NearbyWire.paginate(3, delta = true, emptyList())
        assertEquals(1, pages.size)
        assertTrue(pages[0].last && pages[0].delta && pages[0].items.isEmpty())
    }

    @Test
    fun `delivery items with recipients paginate within the frame bound`() {
        val items = (0 until 5_000).map { InventoryItem(hexId(it), "d", r = hexId(it + 1), e = hexId(it + 2)) }
        val pages = NearbyWire.paginate(1, delta = false, items)
        pages.forEach { assertTrue(NearbyWire.encode(it).size <= NearbyWire.MAX_FRAME_BYTES) }
        assertEquals(items.size, pages.sumOf { it.items.size })
    }

    @Test
    fun `records are chunked and each chunk fits in a frame`() {
        val json = "x".repeat(NearbyWire.MAX_RECORD_CHUNK_CHARS * 3 + 7)
        val chunks = NearbyWire.chunk(1, hexId(1), "e", json)
        assertEquals(4, chunks.size)
        chunks.forEach { assertTrue(NearbyWire.encode(it).size <= NearbyWire.MAX_FRAME_BYTES) }
        assertEquals(json, chunks.joinToString("") { it.data })
        assertTrue(chunks.all { it.parts == 4 })
    }

    @Test
    fun `oversized records are refused`() {
        val json = "x".repeat(NearbyWire.MAX_RECORD_CHUNK_CHARS * (NearbyWire.MAX_RECORD_PARTS + 1))
        try {
            NearbyWire.chunk(1, hexId(1), "e", json)
            assertFalse("expected IllegalArgumentException", true)
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
