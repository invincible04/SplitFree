package com.splitfree.domain.crypto

import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom

/**
 * Extended NIP-01 tests: JSON edge cases, fromJson robustness, event signing edge cases.
 */
class Nip01ExtendedTest {

    private val privKey = "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a".hexToBytes()
    private val pubHex = NostrEvent.pubkeyFromPrivkey(privKey)

    // --- fromJson robustness ---

    @Test
    fun `fromJson handles extra fields gracefully`() {
        val json = """{"id":"abc","pubkey":"def","created_at":1,"kind":1,"tags":[],"content":"hi","sig":"sig","extra_field":"ignored"}"""
        val event = NostrEvent.fromJson(json)
        assertNotNull(event)
        assertEquals("abc", event!!.id)
        assertEquals("hi", event.content)
    }

    @Test
    fun `fromJson handles missing optional fields`() {
        val json = """{"pubkey":"def","created_at":1,"kind":1,"content":"hi"}"""
        val event = NostrEvent.fromJson(json)
        assertNotNull(event)
        assertEquals("", event!!.id)
        assertEquals("", event.sig)
        assertEquals(emptyList<List<String>>(), event.tags)
    }

    @Test
    fun `fromJson returns null for completely invalid JSON`() {
        assertNull(NostrEvent.fromJson(""))
        assertNull(NostrEvent.fromJson("null"))
        assertNull(NostrEvent.fromJson("[]"))
        assertNull(NostrEvent.fromJson("{"))
        assertNull(NostrEvent.fromJson("not json at all"))
    }

    @Test
    fun `fromJson handles nested tags correctly`() {
        val json = """{"id":"","pubkey":"","created_at":0,"kind":0,"tags":[["e","abc","wss://relay.example.com"],["p","def"]],"content":"","sig":""}"""
        val event = NostrEvent.fromJson(json)!!
        assertEquals(2, event.tags.size)
        assertEquals(listOf("e", "abc", "wss://relay.example.com"), event.tags[0])
        assertEquals(listOf("p", "def"), event.tags[1])
    }

    // --- toJson / fromJson round-trip ---

    @Test
    fun `toJson-fromJson round-trip preserves all fields`() {
        val original = NostrEvent(
            id = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
            pubkey = pubHex,
            createdAt = 1700000000L,
            kind = 30078,
            tags = listOf(
                listOf("d", "group:uuid"),
                listOf("g", "group-id"),
                listOf("t", "expense"),
                listOf("e", "expense-uuid")
            ),
            content = "encrypted content here",
            sig = "a".repeat(128)
        )
        val json = original.toJson()
        val parsed = NostrEvent.fromJson(json)!!
        assertEquals(original.id, parsed.id)
        assertEquals(original.pubkey, parsed.pubkey)
        assertEquals(original.createdAt, parsed.createdAt)
        assertEquals(original.kind, parsed.kind)
        assertEquals(original.tags, parsed.tags)
        assertEquals(original.content, parsed.content)
        assertEquals(original.sig, parsed.sig)
    }

    @Test
    fun `toJson-fromJson round-trip with special characters in content`() {
        val content = "line1\nline2\rcarriage\ttab\bbackspace\u000Cformfeed\"quote\\backslash"
        val event = NostrEvent(pubkey = pubHex, createdAt = 1, kind = 1, content = content).sign(privKey)
        val parsed = NostrEvent.fromJson(event.toJson())!!
        assertEquals(content, parsed.content)
        assertTrue(parsed.verify())
    }

    @Test
    fun `toJson-fromJson round-trip with unicode in content`() {
        val content = "Dinner 🍕 at café — ₹500 日本語 العربية"
        val event = NostrEvent(pubkey = pubHex, createdAt = 1, kind = 1, content = content).sign(privKey)
        val parsed = NostrEvent.fromJson(event.toJson())!!
        assertEquals(content, parsed.content)
        assertTrue(parsed.verify())
    }

    @Test
    fun `toJson-fromJson round-trip with empty tags and content`() {
        val event = NostrEvent(pubkey = pubHex, createdAt = 1, kind = 1, tags = emptyList(), content = "").sign(privKey)
        // NIP-44 requires plaintext >= 1 byte, but NIP-01 events can have empty content
        // The event itself should round-trip fine
        val parsed = NostrEvent.fromJson(event.toJson())!!
        assertEquals("", parsed.content)
        assertEquals(emptyList<List<String>>(), parsed.tags)
    }

    // --- Signing edge cases ---

    @Test
    fun `sign produces different signatures with different aux randomness`() {
        val event = NostrEvent(pubkey = pubHex, createdAt = 1, kind = 1, content = "test")
        val sig1 = event.sign(privKey).sig
        val sig2 = event.sign(privKey).sig
        // BIP-340 with random aux data should produce different sigs
        // (unless aux randomness happens to be identical, astronomically unlikely)
        assertNotEquals("Signatures should differ due to random aux", sig1, sig2)
    }

    @Test
    fun `verify rejects event with modified tag`() {
        val signed = NostrEvent(
            pubkey = pubHex, createdAt = 1, kind = 1,
            tags = listOf(listOf("d", "original")), content = "test"
        ).sign(privKey)
        val tampered = signed.copy(tags = listOf(listOf("d", "tampered")))
        assertFalse(tampered.verify())
    }

    @Test
    fun `verify rejects event with modified kind`() {
        val signed = NostrEvent(pubkey = pubHex, createdAt = 1, kind = 1, content = "test").sign(privKey)
        assertFalse(signed.copy(kind = 2).verify())
    }

    @Test
    fun `verify rejects event with modified timestamp`() {
        val signed = NostrEvent(pubkey = pubHex, createdAt = 1, kind = 1, content = "test").sign(privKey)
        assertFalse(signed.copy(createdAt = 2).verify())
    }

    // --- Hex utilities ---

    @Test
    fun `hex round-trip for all byte values`() {
        val allBytes = ByteArray(256) { it.toByte() }
        val hex = allBytes.toHex()
        assertEquals(512, hex.length)
        assertArrayEquals(allBytes, hex.hexToBytes())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `hexToBytes rejects odd-length string`() {
        "abc".hexToBytes()
    }

    @Test
    fun `hexToBytes handles uppercase`() {
        // Our hexToBytes uses Character.digit which handles both cases
        val bytes = "AABB".hexToBytes()
        assertEquals(0xAA.toByte(), bytes[0])
        assertEquals(0xBB.toByte(), bytes[1])
    }

    // --- pubkeyFromPrivkey ---

    @Test
    fun `pubkeyFromPrivkey is deterministic`() {
        val pub1 = NostrEvent.pubkeyFromPrivkey(privKey)
        val pub2 = NostrEvent.pubkeyFromPrivkey(privKey)
        assertEquals(pub1, pub2)
    }

    @Test
    fun `pubkeyFromPrivkey produces 64-char lowercase hex`() {
        val random = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val pub = NostrEvent.pubkeyFromPrivkey(random)
        assertEquals(64, pub.length)
        assertTrue(pub.all { it in '0'..'9' || it in 'a'..'f' })
    }

    // --- Event ID determinism ---

    @Test
    fun `computeId is deterministic for same event`() {
        val event = NostrEvent(pubkey = pubHex, createdAt = 12345, kind = 1, content = "hello")
        val id1 = event.computeId()
        val id2 = event.computeId()
        assertArrayEquals(id1, id2)
    }

    @Test
    fun `computeId differs for different content`() {
        val e1 = NostrEvent(pubkey = pubHex, createdAt = 1, kind = 1, content = "a")
        val e2 = NostrEvent(pubkey = pubHex, createdAt = 1, kind = 1, content = "b")
        assertFalse(e1.computeId().contentEquals(e2.computeId()))
    }
}
