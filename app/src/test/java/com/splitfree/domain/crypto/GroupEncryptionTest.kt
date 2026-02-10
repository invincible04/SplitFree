package com.splitfree.domain.crypto

import org.junit.Assert.*
import org.junit.Test

/**
 * GroupEncryption tests: encrypt/decrypt with shared group key.
 */
class GroupEncryptionTest {

    private val encryption = GroupEncryption()

    @Test
    fun `generate group key is 32 bytes base64`() {
        val key = encryption.generateGroupKey()
        val decoded = java.util.Base64.getDecoder().decode(key)
        assertEquals(32, decoded.size)
    }

    @Test
    fun `encrypt then decrypt round-trip`() {
        val key = encryption.generateGroupKey()
        val plaintext = """{"amount":42.50,"description":"Dinner","payer":"alice","splits":["bob","charlie"]}"""
        val encrypted = encryption.encrypt(plaintext, key)
        assertNotEquals(plaintext, encrypted)
        val decrypted = encryption.decrypt(encrypted, key)
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun `different group keys cannot decrypt each other`() {
        val key1 = encryption.generateGroupKey()
        val key2 = encryption.generateGroupKey()
        val encrypted = encryption.encrypt("secret", key1)
        try {
            val result = encryption.decrypt(encrypted, key2)
            // If it doesn't throw, the result should be garbage (MAC check should fail)
            fail("Should throw on wrong key, got: $result")
        } catch (_: Exception) {
            // expected — MAC verification fails
        }
    }

    @Test
    fun `same key deterministically derives same conversation key`() {
        val key = encryption.generateGroupKey()
        // Encrypt twice — should produce different ciphertexts (random nonce)
        // but both should decrypt to the same plaintext
        val msg = "test message"
        val e1 = encryption.encrypt(msg, key)
        val e2 = encryption.encrypt(msg, key)
        assertNotEquals("Ciphertexts should differ (random nonce)", e1, e2)
        assertEquals(msg, encryption.decrypt(e1, key))
        assertEquals(msg, encryption.decrypt(e2, key))
    }

    @Test
    fun `handles empty-ish and unicode content`() {
        val key = encryption.generateGroupKey()
        // Single char
        assertEquals("a", encryption.decrypt(encryption.encrypt("a", key), key))
        // Unicode
        val unicode = "Ünïcödé 🎉 日本語"
        assertEquals(unicode, encryption.decrypt(encryption.encrypt(unicode, key), key))
        // Long JSON
        val long = """{"data":"${"x".repeat(5000)}"}"""
        assertEquals(long, encryption.decrypt(encryption.encrypt(long, key), key))
    }
}
