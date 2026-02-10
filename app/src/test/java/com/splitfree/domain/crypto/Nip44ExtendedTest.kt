package com.splitfree.domain.crypto

import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom

/**
 * Extended NIP-44 tests beyond the official test vectors.
 * Tests edge cases that could cause real-world failures.
 */
class Nip44ExtendedTest {

    private val privA = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val privB = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val pubB = NostrEvent.pubkeyFromPrivkey(privB).hexToBytes()

    @Test
    fun `conversation key is symmetric`() {
        val pubA = NostrEvent.pubkeyFromPrivkey(privA).hexToBytes()
        val keyAB = Nip44.getConversationKey(privA, pubB)
        val keyBA = Nip44.getConversationKey(privB, pubA)
        assertArrayEquals("conv(a,B) must equal conv(b,A)", keyAB, keyBA)
    }

    @Test
    fun `encrypt-decrypt with 1-byte plaintext (minimum)`() {
        val convKey = Nip44.getConversationKey(privA, pubB)
        val encrypted = Nip44.encrypt("a", convKey)
        assertEquals("a", Nip44.decrypt(encrypted, convKey))
    }

    @Test
    fun `encrypt-decrypt with max-length plaintext`() {
        val convKey = Nip44.getConversationKey(privA, pubB)
        val plaintext = "x".repeat(65535)
        val encrypted = Nip44.encrypt(plaintext, convKey)
        assertEquals(plaintext, Nip44.decrypt(encrypted, convKey))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encrypt rejects empty plaintext`() {
        val convKey = Nip44.getConversationKey(privA, pubB)
        Nip44.encrypt("", convKey)
    }

    @Test
    fun `encrypt-decrypt with all JSON special characters`() {
        val convKey = Nip44.getConversationKey(privA, pubB)
        val text = "\"\\\n\r\t\b\u000C"
        assertEquals(text, Nip44.decrypt(Nip44.encrypt(text, convKey), convKey))
    }

    @Test
    fun `encrypt-decrypt with emoji and multibyte UTF-8`() {
        val convKey = Nip44.getConversationKey(privA, pubB)
        val text = "🎉🍕💰 café ₹500 日本語"
        assertEquals(text, Nip44.decrypt(Nip44.encrypt(text, convKey), convKey))
    }

    @Test
    fun `different nonces produce different ciphertext`() {
        val convKey = Nip44.getConversationKey(privA, pubB)
        val e1 = Nip44.encrypt("same", convKey)
        val e2 = Nip44.encrypt("same", convKey)
        assertNotEquals(e1, e2)
    }

    @Test(expected = Exception::class)
    fun `decrypt rejects tampered ciphertext`() {
        val convKey = Nip44.getConversationKey(privA, pubB)
        val encrypted = Nip44.encrypt("hello", convKey)
        // Flip a character in the middle of the base64
        val tampered = encrypted.substring(0, 50) + "X" + encrypted.substring(51)
        Nip44.decrypt(tampered, convKey)
    }

    @Test(expected = Exception::class)
    fun `decrypt rejects wrong conversation key`() {
        val convKey = Nip44.getConversationKey(privA, pubB)
        val wrongKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val encrypted = Nip44.encrypt("secret", convKey)
        Nip44.decrypt(encrypted, wrongKey)
    }

    @Test(expected = Exception::class)
    fun `decrypt rejects version 0x01`() {
        val convKey = Nip44.getConversationKey(privA, pubB)
        val encrypted = Nip44.encrypt("test", convKey)
        val decoded = java.util.Base64.getDecoder().decode(encrypted)
        decoded[0] = 0x01 // change version
        val reencoded = java.util.Base64.getEncoder().encodeToString(decoded)
        Nip44.decrypt(reencoded, convKey)
    }

    @Test(expected = Exception::class)
    fun `decrypt rejects payload starting with hash`() {
        val convKey = Nip44.getConversationKey(privA, pubB)
        Nip44.decrypt("#future-version-payload", convKey)
    }

    // --- Padding ---

    @Test
    fun `calcPaddedLen matches spec for small values`() {
        assertEquals(32, Nip44.calcPaddedLen(1))
        assertEquals(32, Nip44.calcPaddedLen(32))
        assertEquals(64, Nip44.calcPaddedLen(33))
        assertEquals(64, Nip44.calcPaddedLen(64))
        assertEquals(96, Nip44.calcPaddedLen(65))
    }

    @Test
    fun `calcPaddedLen is always at least 32`() {
        for (i in 1..32) {
            assertEquals("Len $i should pad to 32", 32, Nip44.calcPaddedLen(i))
        }
    }

    @Test
    fun `calcPaddedLen is monotonically non-decreasing`() {
        var prev = 0
        for (i in 1..1000) {
            val padded = Nip44.calcPaddedLen(i)
            assertTrue("Padded length must not decrease", padded >= prev)
            assertTrue("Padded length must be >= input", padded >= i)
            prev = padded
        }
    }

    // --- HKDF ---

    @Test
    fun `hkdfExtract produces 32-byte output`() {
        val result = Nip44.hkdfExtract("salt".toByteArray(), "ikm".toByteArray())
        assertEquals(32, result.size)
    }

    @Test
    fun `hkdfExpand produces requested length`() {
        val prk = Nip44.hkdfExtract("salt".toByteArray(), "ikm".toByteArray())
        assertEquals(76, Nip44.hkdfExpand(prk, "info".toByteArray(), 76).size)
        assertEquals(32, Nip44.hkdfExpand(prk, "info".toByteArray(), 32).size)
        assertEquals(1, Nip44.hkdfExpand(prk, "info".toByteArray(), 1).size)
    }

    @Test
    fun `hkdfExpand is deterministic`() {
        val prk = Nip44.hkdfExtract("salt".toByteArray(), "ikm".toByteArray())
        val a = Nip44.hkdfExpand(prk, "info".toByteArray(), 76)
        val b = Nip44.hkdfExpand(prk, "info".toByteArray(), 76)
        assertArrayEquals(a, b)
    }
}
