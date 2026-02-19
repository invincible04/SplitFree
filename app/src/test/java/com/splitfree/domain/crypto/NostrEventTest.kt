package com.splitfree.domain.crypto

import com.splitfree.domain.util.hexToBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Additional branch coverage for NostrEvent (Nip01.kt):
 * - escapeJson branches (all special chars)
 * - fromJson with missing/null fields
 * - verify with empty/invalid fields
 * - toJson round-trip
 */
class NostrEventTest {
    private val privKey = "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a".hexToBytes()
    private val pubHex = NostrEvent.pubkeyFromPrivkey(privKey)

    // --- escapeJson branches (exercised via toJson) ---

    @Test
    fun `toJson escapes newline`() {
        val e = NostrEvent("", pubHex, 0, 1, emptyList(), "line1\nline2", "")
        assertTrue(e.toJson().contains("\\n"))
    }

    @Test
    fun `toJson escapes carriage return`() {
        val e = NostrEvent("", pubHex, 0, 1, emptyList(), "a\rb", "")
        assertTrue(e.toJson().contains("\\r"))
    }

    @Test
    fun `toJson escapes tab`() {
        val e = NostrEvent("", pubHex, 0, 1, emptyList(), "a\tb", "")
        assertTrue(e.toJson().contains("\\t"))
    }

    @Test
    fun `toJson escapes backspace`() {
        val e = NostrEvent("", pubHex, 0, 1, emptyList(), "a\bb", "")
        assertTrue(e.toJson().contains("\\b"))
    }

    @Test
    fun `toJson escapes form feed`() {
        val e = NostrEvent("", pubHex, 0, 1, emptyList(), "a\u000Cb", "")
        assertTrue(e.toJson().contains("\\f"))
    }

    @Test
    fun `toJson escapes double quote`() {
        val e = NostrEvent("", pubHex, 0, 1, emptyList(), "say \"hi\"", "")
        assertTrue(e.toJson().contains("\\\""))
    }

    @Test
    fun `toJson escapes backslash`() {
        val e = NostrEvent("", pubHex, 0, 1, emptyList(), "a\\b", "")
        assertTrue(e.toJson().contains("\\\\"))
    }

    @Test
    fun `toJson escapes low control chars as unicode`() {
        val e = NostrEvent("", pubHex, 0, 1, emptyList(), "a\u0001b", "")
        assertTrue(e.toJson().contains("\\u0001"))
    }

    @Test
    fun `toJson with tags`() {
        val e = NostrEvent("", pubHex, 0, 1, listOf(listOf("g", "group1"), listOf("t", "expense")), "hi", "")
        val json = e.toJson()
        assertTrue(json.contains("\"g\""))
        assertTrue(json.contains("\"group1\""))
    }

    // --- fromJson branches ---

    @Test
    fun `fromJson with all fields`() {
        val json =
            """{"id":"abc","pubkey":"def","created_at":123,"kind":1,""" +
                """"tags":[["g","x"]],"content":"hello","sig":"sig1"}"""
        val e = NostrEvent.fromJson(json)
        assertNotNull(e)
        assertEquals("abc", e!!.id)
        assertEquals("def", e.pubkey)
        assertEquals(123L, e.createdAt)
        assertEquals(1, e.kind)
        assertEquals(listOf(listOf("g", "x")), e.tags)
        assertEquals("hello", e.content)
        assertEquals("sig1", e.sig)
    }

    @Test
    fun `fromJson with missing fields uses defaults`() {
        val json = """{"kind":1}"""
        val e = NostrEvent.fromJson(json)
        assertNotNull(e)
        assertEquals("", e!!.id)
        assertEquals("", e.pubkey)
        assertEquals(0L, e.createdAt)
        assertEquals(emptyList<List<String>>(), e.tags)
        assertEquals("", e.content)
        assertEquals("", e.sig)
    }

    @Test
    fun `fromJson with invalid JSON returns null`() {
        assertNull(NostrEvent.fromJson("not json"))
    }

    @Test
    fun `fromJson with empty object`() {
        val e = NostrEvent.fromJson("{}")
        assertNotNull(e)
        assertEquals(0, e!!.kind)
    }

    // --- verify branches ---

    @Test
    fun `verify returns false for empty id`() {
        val e = NostrEvent("", pubHex, 0, 1, emptyList(), "", "sig")
        assertFalse(e.verify())
    }

    @Test
    fun `verify returns false for empty sig`() {
        val e = NostrEvent("abc", pubHex, 0, 1, emptyList(), "", "")
        assertFalse(e.verify())
    }

    @Test
    fun `verify returns false for empty pubkey`() {
        val e = NostrEvent("abc", "", 0, 1, emptyList(), "", "sig")
        assertFalse(e.verify())
    }

    @Test
    fun `verify returns false for mismatched id`() {
        val e = NostrEvent("wrongid", pubHex, 0, 1, emptyList(), "", "aa".repeat(32))
        assertFalse(e.verify())
    }

    @Test
    fun `sign and verify round-trip`() {
        val unsigned = NostrEvent("", pubHex, System.currentTimeMillis() / 1000, 1, emptyList(), "test", "")
        val signed = unsigned.sign(privKey)
        assertTrue(signed.verify())
    }

    @Test
    fun `verify catches exception for invalid sig hex`() {
        // Create a valid event but corrupt the sig to cause an exception in verifySchnorr
        val unsigned = NostrEvent("", pubHex, 1, 1, emptyList(), "test", "")
        val signed = unsigned.sign(privKey)
        val tampered = signed.copy(sig = "zz".repeat(32)) // invalid hex
        assertFalse(tampered.verify())
    }

    // --- hexToBytes ---

    @Test
    fun `hexToBytes rejects odd length`() {
        assertThrows(IllegalArgumentException::class.java) { "abc".hexToBytes() }
    }

    // --- toJson and fromJson round-trip ---

    @Test
    fun `toJson and fromJson round-trip`() {
        val original =
            NostrEvent(
                "id1",
                pubHex,
                12345,
                30078,
                listOf(listOf("g", "grp"), listOf("t", "expense")),
                "content",
                "sig1"
            )
        val json = original.toJson()
        val parsed = NostrEvent.fromJson(json)
        assertNotNull(parsed)
        assertEquals(original.id, parsed!!.id)
        assertEquals(original.content, parsed.content)
        assertEquals(original.tags, parsed.tags)
    }
}
