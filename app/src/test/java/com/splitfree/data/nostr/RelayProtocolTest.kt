package com.splitfree.data.nostr

import com.splitfree.domain.crypto.NostrEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RelayProtocolTest {
    private val sampleEvent = NostrEvent("id1", "pub1", 100, 1, listOf(listOf("g", "grp")), "hello", "sig1")

    // --- RelayMessage.parse ---

    @Test
    fun `parse EVENT message`() {
        val json = """["EVENT","sub1",{"id":"id1","pubkey":"pub1","created_at":100,"kind":1,"tags":[["g","grp"]],"content":"hello","sig":"sig1"}]"""
        val msg = RelayMessage.parse(json) as RelayMessage.EventMsg
        assertEquals("sub1", msg.subId)
        assertEquals("id1", msg.event.id)
        assertEquals("hello", msg.event.content)
        assertEquals(listOf(listOf("g", "grp")), msg.event.tags)
    }

    @Test
    fun `parse OK message`() {
        val json = """["OK","eid1",true,"saved"]"""
        val msg = RelayMessage.parse(json) as RelayMessage.OkMsg
        assertEquals("eid1", msg.eventId)
        assertTrue(msg.accepted)
        assertEquals("saved", msg.message)
    }

    @Test
    fun `parse OK message without message`() {
        val json = """["OK","eid1",false]"""
        val msg = RelayMessage.parse(json) as RelayMessage.OkMsg
        assertFalse(msg.accepted)
        assertEquals("", msg.message)
    }

    @Test
    fun `parse EOSE message`() {
        val json = """["EOSE","sub1"]"""
        val msg = RelayMessage.parse(json) as RelayMessage.EoseMsg
        assertEquals("sub1", msg.subId)
    }

    @Test
    fun `parse CLOSED message`() {
        val json = """["CLOSED","sub1","rate-limited"]"""
        val msg = RelayMessage.parse(json) as RelayMessage.ClosedMsg
        assertEquals("sub1", msg.subId)
        assertEquals("rate-limited", msg.message)
    }

    @Test
    fun `parse NOTICE message`() {
        val json = """["NOTICE","slow down"]"""
        val msg = RelayMessage.parse(json) as RelayMessage.NoticeMsg
        assertEquals("slow down", msg.message)
    }

    @Test
    fun `parse AUTH message`() {
        val json = """["AUTH","challenge123"]"""
        val msg = RelayMessage.parse(json) as RelayMessage.AuthMsg
        assertEquals("challenge123", msg.challenge)
    }

    @Test
    fun `parse unknown type returns null`() {
        assertNull(RelayMessage.parse("""["UNKNOWN","data"]"""))
    }

    @Test
    fun `parse invalid JSON returns null`() {
        assertNull(RelayMessage.parse("not json"))
    }

    // --- ClientMessage ---

    @Test
    fun `Event toJson`() {
        val json = ClientMessage.Event(sampleEvent).toJson()
        assertTrue(json.startsWith("[\"EVENT\","))
        assertTrue(json.contains("\"id1\""))
    }

    @Test
    fun `Req toJson`() {
        val filter = NostrFilter(kinds = listOf(1), limit = 10)
        val json = ClientMessage.Req("sub1", listOf(filter)).toJson()
        assertTrue(json.startsWith("[\"REQ\",\"sub1\","))
        assertTrue(json.contains("\"kinds\""))
    }

    @Test
    fun `Close toJson`() {
        val json = ClientMessage.Close("sub1").toJson()
        assertEquals("""["CLOSE","sub1"]""", json)
    }

    @Test
    fun `Auth toJson`() {
        val json = ClientMessage.Auth(sampleEvent).toJson()
        assertTrue(json.startsWith("[\"AUTH\","))
    }

    @Test
    fun `Req escapes special chars in subId`() {
        val json = ClientMessage.Req("sub\"1\\2", listOf()).toJson()
        assertTrue(json.contains("sub\\\"1\\\\2"))
    }

    @Test
    fun `Close escapes special chars in subId`() {
        val json = ClientMessage.Close("sub\"x").toJson()
        assertTrue(json.contains("sub\\\"x"))
    }

    // --- NostrFilter ---

    @Test
    fun `NostrFilter toJson with all fields`() {
        val f =
            NostrFilter(
                kinds = listOf(1, 30078),
                authors = listOf("pub1"),
                ids = listOf("id1"),
                tags = mapOf("#d" to listOf("val")),
                since = 100,
                until = 200,
                limit = 50,
            )
        val json = f.toJson()
        assertTrue(json.contains("\"kinds\""))
        assertTrue(json.contains("\"authors\""))
        assertTrue(json.contains("\"ids\""))
        assertTrue(json.contains("\"#d\""))
        assertTrue(json.contains("\"since\""))
        assertTrue(json.contains("\"until\""))
        assertTrue(json.contains("\"limit\""))
    }

    @Test
    fun `NostrFilter toJson with no fields`() {
        val json = NostrFilter().toJson()
        assertEquals("{}", json)
    }

    @Test
    fun `NostrFilter toJson with only kinds`() {
        val json = NostrFilter(kinds = listOf(1)).toJson()
        assertTrue(json.contains("\"kinds\""))
        assertFalse(json.contains("\"authors\""))
    }
}
