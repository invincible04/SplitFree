package com.splitfree.domain.crypto

import com.splitfree.data.nostr.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Relay protocol message parsing and serialization tests.
 */
class RelayProtocolTest {
    @Test
    fun `parse EVENT message`() {
        val json = """["EVENT","sub1",{"id":"abc","pubkey":"def","created_at":123,"kind":1,"tags":[["e","xyz"]],"content":"hello","sig":"sig1"}]"""
        val msg = RelayMessage.parse(json)
        assertTrue(msg is RelayMessage.EventMsg)
        val event = (msg as RelayMessage.EventMsg)
        assertEquals("sub1", event.subId)
        assertEquals("abc", event.event.id)
        assertEquals("def", event.event.pubkey)
        assertEquals(123L, event.event.createdAt)
        assertEquals(1, event.event.kind)
        assertEquals(listOf(listOf("e", "xyz")), event.event.tags)
        assertEquals("hello", event.event.content)
        assertEquals("sig1", event.event.sig)
    }

    @Test
    fun `parse OK message`() {
        val msg = RelayMessage.parse("""["OK","eventid1",true,""]""")
        assertTrue(msg is RelayMessage.OkMsg)
        val ok = msg as RelayMessage.OkMsg
        assertEquals("eventid1", ok.eventId)
        assertTrue(ok.accepted)
    }

    @Test
    fun `parse OK rejected message`() {
        val msg = RelayMessage.parse("""["OK","eventid1",false,"blocked: not allowed"]""")
        assertTrue(msg is RelayMessage.OkMsg)
        assertFalse((msg as RelayMessage.OkMsg).accepted)
        assertEquals("blocked: not allowed", msg.message)
    }

    @Test
    fun `parse EOSE message`() {
        val msg = RelayMessage.parse("""["EOSE","sub123"]""")
        assertTrue(msg is RelayMessage.EoseMsg)
        assertEquals("sub123", (msg as RelayMessage.EoseMsg).subId)
    }

    @Test
    fun `parse CLOSED message`() {
        val msg = RelayMessage.parse("""["CLOSED","sub1","reason"]""")
        assertTrue(msg is RelayMessage.ClosedMsg)
        assertEquals("sub1", (msg as RelayMessage.ClosedMsg).subId)
    }

    @Test
    fun `parse NOTICE message`() {
        val msg = RelayMessage.parse("""["NOTICE","rate limited"]""")
        assertTrue(msg is RelayMessage.NoticeMsg)
        assertEquals("rate limited", (msg as RelayMessage.NoticeMsg).message)
    }

    @Test
    fun `parse AUTH message`() {
        val msg = RelayMessage.parse("""["AUTH","challenge123"]""")
        assertTrue(msg is RelayMessage.AuthMsg)
        assertEquals("challenge123", (msg as RelayMessage.AuthMsg).challenge)
    }

    @Test
    fun `parse returns null for invalid messages`() {
        assertNull(RelayMessage.parse("not json"))
        assertNull(RelayMessage.parse("{}"))
        assertNull(RelayMessage.parse("""["UNKNOWN","data"]"""))
    }

    @Test
    fun `ClientMessage Event serialization`() {
        val event =
            NostrEvent(
                id = "abc",
                pubkey = "def",
                createdAt = 1,
                kind = 1,
                tags = emptyList(),
                content = "hi",
                sig = "sig",
            )
        val json = ClientMessage.Event(event).toJson()
        assertTrue(json.startsWith("[\"EVENT\",{"))
        assertTrue(json.contains("\"id\":\"abc\""))
    }

    @Test
    fun `ClientMessage Req serialization`() {
        val filter = NostrFilter(kinds = listOf(30078), tags = mapOf("#d" to listOf("group1")))
        val json = ClientMessage.Req("sub1", listOf(filter)).toJson()
        assertTrue(json.startsWith("[\"REQ\",\"sub1\","))
        assertTrue(json.contains("\"kinds\":[30078]"))
        assertTrue(json.contains("\"#d\":[\"group1\"]"))
    }

    @Test
    fun `ClientMessage Close serialization`() {
        assertEquals("""["CLOSE","sub1"]""", ClientMessage.Close("sub1").toJson())
    }

    @Test
    fun `NostrFilter with all fields`() {
        val filter =
            NostrFilter(
                kinds = listOf(1, 30078),
                authors = listOf("abc"),
                since = 1000L,
                until = 2000L,
                limit = 50,
            )
        val json = filter.toJson()
        assertTrue(json.contains("\"kinds\""))
        assertTrue(json.contains("\"authors\""))
        assertTrue(json.contains("\"since\""))
        assertTrue(json.contains("\"until\""))
        assertTrue(json.contains("\"limit\""))
    }
}
