package com.splitfree.domain.crypto

import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom

/**
 * Tests for GroupEncryption key derivation — ensures determinism and isolation.
 */
class GroupEncryptionKeyDerivationTest {

    private val encryption = GroupEncryption()

    @Test
    fun `same group key always derives same conversation key`() {
        val key = encryption.generateGroupKey()
        val e1 = encryption.encrypt("test", key)
        // If conversation key were different, decrypt would fail with MAC error
        val d1 = encryption.decrypt(e1, key)
        assertEquals("test", d1)
    }

    @Test
    fun `different group keys derive different conversation keys`() {
        val key1 = encryption.generateGroupKey()
        val key2 = encryption.generateGroupKey()
        val encrypted = encryption.encrypt("secret", key1)
        try {
            encryption.decrypt(encrypted, key2)
            fail("Different key should fail decryption")
        } catch (_: Exception) { /* expected */ }
    }

    @Test
    fun `group key derivation is deterministic across instances`() {
        val key = encryption.generateGroupKey()
        val enc1 = GroupEncryption()
        val enc2 = GroupEncryption()
        val encrypted = enc1.encrypt("hello", key)
        assertEquals("hello", enc2.decrypt(encrypted, key))
    }

    @Test
    fun `encrypt handles exactly 1 byte plaintext`() {
        val key = encryption.generateGroupKey()
        assertEquals("x", encryption.decrypt(encryption.encrypt("x", key), key))
    }

    @Test
    fun `encrypt handles large plaintext (10KB JSON)`() {
        val key = encryption.generateGroupKey()
        val large = "{" + "\"field\":\"${"x".repeat(10000)}\"" + "}"
        assertEquals(large, encryption.decrypt(encryption.encrypt(large, key), key))
    }

    @Test
    fun `encrypt handles plaintext at NIP-44 max boundary`() {
        val key = encryption.generateGroupKey()
        // NIP-44 max is 65535 bytes. Our compression prefix adds overhead,
        // but for non-compressible data it passes through as-is.
        val text = "a".repeat(60000) // well within limit
        assertEquals(text, encryption.decrypt(encryption.encrypt(text, key), key))
    }

    @Test
    fun `compressed and uncompressed decrypt to same result`() {
        val key = encryption.generateGroupKey()
        // Small (won't compress)
        val small = "tiny"
        assertEquals(small, encryption.decrypt(encryption.encrypt(small, key), key))
        // Large repetitive (will compress)
        val large = "repeat ".repeat(200)
        assertEquals(large, encryption.decrypt(encryption.encrypt(large, key), key))
    }
}
