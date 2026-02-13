package com.splitfree.domain.crypto

import org.junit.Assert.*
import org.junit.Test

class GroupEncryptionBranchTest {

    private val enc = GroupEncryption()
    private val key = enc.generateGroupKey()

    @Test
    fun `encrypt rejects empty plaintext`() {
        assertThrows(IllegalArgumentException::class.java) { enc.encrypt("", key) }
    }

    @Test
    fun `decrypt invalid key size rejects`() {
        val shortKey = java.util.Base64.getEncoder().encodeToString(ByteArray(16))
        assertThrows(IllegalArgumentException::class.java) { enc.decrypt("anything", shortKey) }
    }

    @Test
    fun `encrypt with compression for large payload`() {
        val large = "A".repeat(2000)
        val encrypted = enc.encrypt(large, key)
        val decrypted = enc.decrypt(encrypted, key)
        assertEquals(large, decrypted)
    }

    @Test
    fun `encrypt without compression for small payload`() {
        val small = "hello"
        val encrypted = enc.encrypt(small, key)
        val decrypted = enc.decrypt(encrypted, key)
        assertEquals(small, decrypted)
    }

    @Test
    fun `decrypt non-compressed content`() {
        val encrypted = enc.encrypt("short", key)
        assertEquals("short", enc.decrypt(encrypted, key))
    }

    @Test
    fun `generateGroupKey produces unique keys`() {
        val k1 = enc.generateGroupKey()
        val k2 = enc.generateGroupKey()
        assertNotEquals(k1, k2)
    }
}
