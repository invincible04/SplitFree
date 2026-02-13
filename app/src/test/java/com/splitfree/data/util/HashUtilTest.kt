package com.splitfree.data.util

import org.junit.Assert.*
import org.junit.Test

class HashUtilTest {
    @Test
    fun `sha256 of empty string matches known hash`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            HashUtil.sha256Hex(""),
        )
    }

    @Test
    fun `sha256 of hello world matches known hash`() {
        assertEquals(
            "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9",
            HashUtil.sha256Hex("hello world"),
        )
    }

    @Test
    fun `sha256 produces 64 char lowercase hex`() {
        val hash = HashUtil.sha256Hex("test input")
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `sha256 is deterministic`() {
        val input = "deterministic-check"
        assertEquals(HashUtil.sha256Hex(input), HashUtil.sha256Hex(input))
    }

    @Test
    fun `sha256 different inputs produce different hashes`() {
        assertNotEquals(HashUtil.sha256Hex("input1"), HashUtil.sha256Hex("input2"))
    }

    @Test
    fun `sha256 handles unicode`() {
        val hash = HashUtil.sha256Hex("🍕café₹")
        assertEquals(64, hash.length)
    }
}
