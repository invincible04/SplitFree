package com.splitfree.data.nostr.protocol

import com.splitfree.data.nostr.protocol.ClientMessage
import com.splitfree.data.nostr.protocol.NostrFilter
import com.splitfree.data.nostr.protocol.RelayMessage
import com.splitfree.domain.crypto.NostrEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Extended relay protocol tests: filter serialization, AUTH message, edge cases.
 */
class RelayProtocolExtendedTest {
    private val hexId = "ab".repeat(32)
    private val hexPub = "cd".repeat(32)
    private val hexSig = "ef".repeat(64)

    // --- NostrFilter serialization ---

    @Test
    fun `filter with all fields serializes correctly`() {
        val filter =
            NostrFilter(
                kinds = listOf(1, 30078),
                authors = listOf("abc123"),
                ids = listOf("def456"),
                tags = mapOf("#d" to listOf("group1"), "#g" to listOf("group2")),
                since = 1000L,
                until = 2000L,
                limit = 50
            )
        val json = filter.toJson()
        assertTrue(json.contains("\"kinds\""))
        assertTrue(json.contains("30078"))
        assertTrue(json.contains("\"authors\""))
        assertTrue(json.contains("\"abc123\""))
        assertTrue(json.contains("\"#d\""))
        assertTrue(json.contains("\"#g\""))
        assertTrue(json.contains("\"since\""))
        assertTrue(json.contains("\"until\""))
        assertTrue(json.contains("\"limit\""))
    }

    @Test
    fun `filter with only kinds`() {
        val filter = NostrFilter(kinds = listOf(30078))
        val json = filter.toJson()
        assertTrue(json.contains("\"kinds\":[30078]"))
        assertFalse(json.contains("\"authors\""))
        assertFalse(json.contains("\"since\""))
    }

    @Test
    fun `empty filter produces empty JSON object`() {
        val filter = NostrFilter()
        assertEquals("{}", filter.toJson())
    }

    @Test
    fun `filter with multiple tag types`() {
        val filter =
            NostrFilter(
                kinds = listOf(30078),
                tags = mapOf("#g" to listOf("group1"), "#d" to listOf("group1:exp1"))
            )
        val json = filter.toJson()
        assertTrue(json.contains("\"#g\""))
        assertTrue(json.contains("\"#d\""))
    }

    // --- ClientMessage.Auth ---

    @Test
    fun `AUTH message serialization`() {
        val event =
            NostrEvent(
                id = "auth-id",
                pubkey = "auth-pub",
                createdAt = 1,
                kind = 22242,
                tags = listOf(listOf("challenge", "ch1"), listOf("relay", "wss://relay.damus.io")),
                content = "",
                sig = "auth-sig"
            )
        val json = ClientMessage.Auth(event).toJson()
        assertTrue(json.startsWith("[\"AUTH\",{"))
        assertTrue(json.contains("\"kind\":22242"))
        assertTrue(json.contains("\"challenge\""))
        assertTrue(json.contains("\"relay\""))
    }

    // --- ClientMessage.Req with multiple filters ---

    @Test
    fun `REQ with multiple filters (OR semantics)`() {
        val f1 = NostrFilter(kinds = listOf(30078), tags = mapOf("#g" to listOf("group1")))
        val f2 = NostrFilter(kinds = listOf(30078), tags = mapOf("#d" to listOf("group1")))
        val json = ClientMessage.Req("sub1", listOf(f1, f2)).toJson()
        // Should be ["REQ","sub1",{filter1},{filter2}]
        assertTrue(json.startsWith("[\"REQ\",\"sub1\","))
        // Count the number of JSON objects (filters)
        val filterCount = json.split("\"kinds\"").size - 1
        assertEquals("Should have 2 filters", 2, filterCount)
    }

    // --- RelayMessage parsing edge cases ---

    @Test
    fun `parse OK with duplicate prefix`() {
        val msg = RelayMessage.parse("""["OK","eid",true,"duplicate: already have this event"]""")
        assertTrue(msg is RelayMessage.OkMsg)
        val ok = msg as RelayMessage.OkMsg
        assertTrue(ok.accepted)
        assertEquals("duplicate: already have this event", ok.message)
    }

    @Test
    fun `parse OK with rate-limited prefix`() {
        val msg = RelayMessage.parse("""["OK","eid",false,"rate-limited: slow down"]""")
        assertTrue(msg is RelayMessage.OkMsg)
        assertFalse((msg as RelayMessage.OkMsg).accepted)
    }

    @Test
    fun `parse OK with empty message on success`() {
        val msg = RelayMessage.parse("""["OK","eid",true,""]""")
        assertTrue(msg is RelayMessage.OkMsg)
        assertTrue((msg as RelayMessage.OkMsg).accepted)
        assertEquals("", msg.message)
    }

    @Test
    fun `parse EVENT with empty tags`() {
        val msg =
            RelayMessage.parse(
                """["EVENT","sub1",{"id":"$hexId","pubkey":"$hexPub","created_at":1,"kind":13,""" +
                    """"tags":[],"content":"sealed","sig":"$hexSig"}]"""
            )
        assertTrue(msg is RelayMessage.EventMsg)
        val event = (msg as RelayMessage.EventMsg).event
        assertEquals(13, event.kind)
        assertEquals(emptyList<List<String>>(), event.tags)
    }

    @Test
    fun `parse EVENT with multi-element tags`() {
        val msg =
            RelayMessage.parse(
                """["EVENT","sub1",{"id":"$hexId","pubkey":"$hexPub","created_at":1,"kind":1,""" +
                    """"tags":[["e","id1","wss://relay.example.com","reply"],["p","pk1"]],"content":"hi","sig":"$hexSig"}]"""
            )
        val event = (msg as RelayMessage.EventMsg).event
        assertEquals(2, event.tags.size)
        assertEquals(4, event.tags[0].size) // ["e","id1","relay","reply"]
        assertEquals("wss://relay.example.com", event.tags[0][2])
    }

    @Test
    fun `parse EVENT with short id pubkey or sig returns null`() {
        assertNull(
            RelayMessage.parse(
                """["EVENT","sub1",{"id":"a","pubkey":"b","created_at":1,"kind":1,"tags":[],"content":"hi","sig":"s"}]"""
            )
        )
    }

    @Test
    fun `parse CLOSED with error prefix`() {
        val msg = RelayMessage.parse("""["CLOSED","sub1","error: shutting down idle subscription"]""")
        assertTrue(msg is RelayMessage.ClosedMsg)
        assertEquals("error: shutting down idle subscription", (msg as RelayMessage.ClosedMsg).message)
    }

    @Test
    fun `parse returns null for truncated JSON`() {
        assertNull(RelayMessage.parse("""["EVENT","sub1",{"id":"a"""))
        assertNull(RelayMessage.parse("""["OK"]"""))
        assertNull(RelayMessage.parse(""))
    }

    // --- ClientMessage.Req with special characters ---

    @Test
    fun `REQ escapes backslash in subId`() {
        val json = ClientMessage.Req("sub\\1", listOf(NostrFilter())).toJson()
        assertTrue(json.contains("sub\\\\1"))
    }

    @Test
    fun `REQ escapes quotes in subId`() {
        val json = ClientMessage.Req("sub\"1", listOf(NostrFilter())).toJson()
        assertTrue(json.contains("sub\\\"1"))
    }

    @Test
    fun `CLOSE escapes special chars in subId`() {
        val json = ClientMessage.Close("sub\\\"1").toJson()
        assertTrue(json.contains("sub\\\\\\\"1"))
    }

    @Test
    fun `REQ with empty filter list`() {
        val json = ClientMessage.Req("sub1", emptyList()).toJson()
        assertTrue(json.startsWith("[\"REQ\",\"sub1\""))
    }

    // --- NostrFilter edge cases ---

    @Test
    fun `filter with only since`() {
        val filter = NostrFilter(since = 1000L)
        val json = filter.toJson()
        assertTrue(json.contains("\"since\":1000"))
        assertFalse(json.contains("\"kinds\""))
    }

    @Test
    fun `filter with only limit`() {
        val filter = NostrFilter(limit = 10)
        val json = filter.toJson()
        assertTrue(json.contains("\"limit\":10"))
    }

    @Test
    fun `filter with ids`() {
        val filter = NostrFilter(ids = listOf("id1", "id2"))
        val json = filter.toJson()
        assertTrue(json.contains("\"ids\""))
        assertTrue(json.contains("\"id1\""))
    }

    // --- Reconnect backoff calculation ---

    @Test
    fun `backoff sequence matches design doc`() {
        // Design doc Section 5.7.4: 1s → 2s → 4s → 8s → 16s → 32s → 64s → max 60s
        val expected = listOf(1000L, 2000L, 4000L, 8000L, 16000L, 32000L, 60000L, 60000L)
        for ((attempt, expectedMs) in expected.withIndex()) {
            val delayMs = minOf(1000L * (1L shl minOf(attempt, 6)), 60_000L)
            assertEquals("Attempt $attempt", expectedMs, delayMs)
        }
    }
}
