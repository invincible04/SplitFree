package com.splitfree.domain.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HexUtilTest {
    @Test
    fun `toHex produces lowercase hex`() {
        val bytes = byteArrayOf(0x0A, 0xFF.toByte(), 0x00, 0x7F)
        assertEquals("0aff007f", bytes.toHex())
    }

    @Test
    fun `empty array produces empty string`() {
        assertEquals("", byteArrayOf().toHex())
    }

    @Test
    fun `hexToBytes decodes valid hex`() {
        assertArrayEquals(byteArrayOf(0x0A, 0xFF.toByte(), 0x00, 0x7F), "0aff007f".hexToBytes())
    }

    @Test
    fun `hexToBytes handles uppercase`() {
        assertArrayEquals(byteArrayOf(0xAB.toByte(), 0xCD.toByte()), "ABCD".hexToBytes())
    }

    @Test
    fun `empty string produces empty array`() {
        assertArrayEquals(byteArrayOf(), "".hexToBytes())
    }

    @Test
    fun `round trip preserves data`() {
        val original = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        assertArrayEquals(original, original.toHex().hexToBytes())
    }

    @Test
    fun `32-byte key round trips`() {
        val key = ByteArray(32) { it.toByte() }
        val hex = key.toHex()
        assertEquals(64, hex.length)
        assertTrue(hex.all { it in '0'..'9' || it in 'a'..'f' })
        assertArrayEquals(key, hex.hexToBytes())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `hexToBytes rejects odd length`() {
        "abc".hexToBytes()
    }
}
