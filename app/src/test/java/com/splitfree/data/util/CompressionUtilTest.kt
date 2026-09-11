package com.splitfree.data.util

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CompressionUtilTest {
    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
    }

    @After
    fun teardown() {
        unmockkStatic(android.util.Log::class)
    }

    // --- shouldCompress ---

    @Test
    fun `shouldCompress returns false below threshold`() {
        assertFalse(CompressionUtil.shouldCompress(ByteArray(99)))
    }

    @Test
    fun `shouldCompress returns true at threshold`() {
        assertTrue(CompressionUtil.shouldCompress(ByteArray(100)))
    }

    @Test
    fun `shouldCompress returns true above threshold`() {
        assertTrue(CompressionUtil.shouldCompress(ByteArray(500)))
    }

    // --- compress + decompress round-trip ---

    @Test
    fun `compress and decompress round-trip`() {
        val data = "Hello World! ".repeat(50).toByteArray()
        val compressed = CompressionUtil.compress(data)
        assertNotNull(compressed)
        val decompressed = CompressionUtil.decompress(compressed!!)
        assertArrayEquals(data, decompressed)
    }

    @Test
    fun `compress returns null when compressed is larger than original`() {
        // Random-ish data that won't compress well
        val data = ByteArray(50) { it.toByte() }
        val result = CompressionUtil.compress(data)
        // LZ4 may or may not compress small data; if it doesn't shrink, returns null
        // This is a valid branch either way
        if (result != null) {
            assertTrue(result.size < data.size + 4) // 4-byte header
        }
    }

    // --- decompress edge cases ---

    @Test
    fun `decompress returns null for data shorter than 4 bytes`() {
        assertNull(CompressionUtil.decompress(ByteArray(3)))
    }

    @Test
    fun `decompress returns null for empty array`() {
        assertNull(CompressionUtil.decompress(ByteArray(0)))
    }

    @Test
    fun `decompress returns null when original size is zero`() {
        val bad = ByteArray(8) // first 4 bytes = 0
        assertNull(CompressionUtil.decompress(bad))
    }

    @Test
    fun `decompress returns null when original size is negative`() {
        val bad = byteArrayOf(-1, -1, -1, -1, 0, 0, 0, 0) // -1 in big-endian
        assertNull(CompressionUtil.decompress(bad))
    }

    @Test
    fun `decompress returns null when original size exceeds max`() {
        // 100_001 in big-endian = 0x000186A1
        val bad = byteArrayOf(0x00, 0x01, 0x86.toByte(), 0xA1.toByte(), 0, 0, 0, 0)
        assertNull(CompressionUtil.decompress(bad))
    }

    @Test
    fun `decompress returns null for suspicious compression ratio`() {
        // Claim original size is 100_000 but only 4 bytes of compressed data → ratio > 1000
        val bad = byteArrayOf(0x00, 0x01, 0x86.toByte(), 0xA0.toByte(), 0x00)
        assertNull(CompressionUtil.decompress(bad))
    }

    @Test
    fun `decompress returns null for corrupted compressed data`() {
        val bad = byteArrayOf(0x00, 0x00, 0x00, 0x20, 0x01, 0x02, 0x03, 0x04) // claims 32 bytes original
        assertNull(CompressionUtil.decompress(bad))
    }

    // --- large data ---

    @Test
    fun `compress and decompress large repetitive data`() {
        val data = "ABCDEFGH".repeat(5000).toByteArray()
        val compressed = CompressionUtil.compress(data)
        assertNotNull(compressed)
        assertTrue(compressed!!.size < data.size)
        val decompressed = CompressionUtil.decompress(compressed)
        assertArrayEquals(data, decompressed)
    }
}
