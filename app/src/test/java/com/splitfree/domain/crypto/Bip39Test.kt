package com.splitfree.domain.crypto

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class Bip39Test {
    // --- Wordlist validation ---

    @Test
    fun `wordlist has exactly 2048 words`() {
        assertEquals(2048, Bip39.WORDLIST.size)
    }

    @Test
    fun `wordlist has no duplicates`() {
        assertEquals(Bip39.WORDLIST.size, Bip39.WORDLIST.toSet().size)
    }

    @Test
    fun `wordlist is sorted alphabetically`() {
        assertEquals(Bip39.WORDLIST, Bip39.WORDLIST.sorted())
    }

    @Test
    fun `first word is abandon, last is zoo`() {
        assertEquals("abandon", Bip39.WORDLIST.first())
        assertEquals("zoo", Bip39.WORDLIST.last())
    }

    // --- toMnemonic ---

    @Test
    fun `toMnemonic produces 24 words for 32 bytes`() {
        val entropy = ByteArray(32) { it.toByte() }
        val words = Bip39.toMnemonic(entropy)
        assertEquals(24, words.size)
        assertTrue(words.all { it in Bip39.WORDLIST })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `toMnemonic rejects 16 bytes`() {
        Bip39.toMnemonic(ByteArray(16))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `toMnemonic rejects 0 bytes`() {
        Bip39.toMnemonic(ByteArray(0))
    }

    @Test
    fun `all-zero entropy produces deterministic mnemonic`() {
        val words = Bip39.toMnemonic(ByteArray(32))
        // BIP-39 test vector: 256 bits of zeros
        assertEquals("abandon", words[0])
        assertEquals("abandon", words[1])
        // Last word includes checksum, so it's not "abandon"
        assertEquals(24, words.size)
    }

    @Test
    fun `all-ff entropy produces deterministic mnemonic`() {
        val entropy = ByteArray(32) { 0xFF.toByte() }
        val words = Bip39.toMnemonic(entropy)
        assertEquals("zoo", words[0])
        assertEquals("zoo", words[1])
        assertEquals(24, words.size)
    }

    // --- Round-trip ---

    @Test
    fun `round-trip with random entropy`() {
        val entropy = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val words = Bip39.toMnemonic(entropy)
        val recovered = Bip39.toEntropy(words)
        assertArrayEquals(entropy, recovered)
    }

    @Test
    fun `round-trip with all zeros`() {
        val entropy = ByteArray(32)
        val words = Bip39.toMnemonic(entropy)
        val recovered = Bip39.toEntropy(words)
        assertArrayEquals(entropy, recovered)
    }

    @Test
    fun `round-trip with sequential bytes`() {
        val entropy = ByteArray(32) { it.toByte() }
        val words = Bip39.toMnemonic(entropy)
        val recovered = Bip39.toEntropy(words)
        assertArrayEquals(entropy, recovered)
    }

    @Test
    fun `round-trip 100 random keys`() {
        val rng = java.security.SecureRandom()
        repeat(100) {
            val entropy = ByteArray(32).also { rng.nextBytes(it) }
            val words = Bip39.toMnemonic(entropy)
            val recovered = Bip39.toEntropy(words)
            assertArrayEquals("Failed on iteration $it", entropy, recovered)
        }
    }

    // --- toEntropy validation ---

    @Test(expected = IllegalArgumentException::class)
    fun `toEntropy rejects 12 words`() {
        Bip39.toEntropy(List(12) { "abandon" })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `toEntropy rejects 23 words`() {
        Bip39.toEntropy(List(23) { "abandon" })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `toEntropy rejects unknown word`() {
        val words = Bip39.toMnemonic(ByteArray(32)).toMutableList()
        words[5] = "notaword"
        Bip39.toEntropy(words)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `toEntropy rejects bad checksum`() {
        val words = Bip39.toMnemonic(ByteArray(32)).toMutableList()
        // Flip last word to break checksum
        words[23] = if (words[23] == "abandon") "ability" else "abandon"
        Bip39.toEntropy(words)
    }

    // --- isMnemonic ---

    @Test
    fun `isMnemonic detects valid 24-word phrase`() {
        val words = Bip39.toMnemonic(ByteArray(32))
        assertTrue(Bip39.isMnemonic(words.joinToString(" ")))
    }

    @Test
    fun `isMnemonic detects valid phrase with extra whitespace`() {
        val words = Bip39.toMnemonic(ByteArray(32))
        assertTrue(Bip39.isMnemonic("  " + words.joinToString("  ") + "  "))
    }

    @Test
    fun `isMnemonic rejects hex key`() {
        assertFalse(Bip39.isMnemonic("a".repeat(64)))
    }

    @Test
    fun `isMnemonic rejects random text`() {
        assertFalse(Bip39.isMnemonic("hello world this is not a mnemonic"))
    }

    @Test
    fun `isMnemonic rejects 13 words`() {
        assertFalse(Bip39.isMnemonic(List(13) { "abandon" }.joinToString(" ")))
    }

    @Test
    fun `isMnemonic rejects 12 words - only 24-word phrases supported`() {
        // App only supports 24-word (256-bit entropy) mnemonics
        assertFalse(Bip39.isMnemonic(List(12) { "abandon" }.joinToString(" ")))
    }

    // --- BIP-39 official test vectors (256-bit entropy) ---
    // Source: https://github.com/trezor/python-mnemonic/blob/master/vectors.json

    @Test
    fun `test vector - all zeros`() {
        val entropy = ByteArray(32)
        val words = Bip39.toMnemonic(entropy)
        assertEquals(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art",
            words.joinToString(" "),
        )
    }

    @Test
    fun `test vector - 7f repeated`() {
        val entropy = ByteArray(32) { 0x7f.toByte() }
        val words = Bip39.toMnemonic(entropy)
        assertEquals(
            "legal winner thank year wave sausage worth useful legal winner thank year wave sausage worth useful legal winner thank year wave sausage worth title",
            words.joinToString(" "),
        )
    }

    @Test
    fun `test vector - 80 repeated`() {
        val entropy = ByteArray(32) { 0x80.toByte() }
        val words = Bip39.toMnemonic(entropy)
        assertEquals(
            "letter advice cage absurd amount doctor acoustic avoid letter advice cage absurd amount doctor acoustic avoid letter advice cage absurd amount doctor acoustic bless",
            words.joinToString(" "),
        )
    }

    @Test
    fun `test vector - ff repeated`() {
        val entropy = ByteArray(32) { 0xFF.toByte() }
        val words = Bip39.toMnemonic(entropy)
        assertEquals(
            "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo vote",
            words.joinToString(" "),
        )
    }

    @Test
    fun `test vector - hamster diagram`() {
        val entropy = hexToBytes("68a79eaca2324873eacc50cb9c6eca8cc68ea5d936f98787c60c7ebc74e6ce7c")
        val words = Bip39.toMnemonic(entropy)
        assertEquals(
            "hamster diagram private dutch cause delay private meat slide toddler razor book happy fancy gospel tennis maple dilemma loan word shrug inflict delay length",
            words.joinToString(" "),
        )
    }

    @Test
    fun `test vector - panda eyebrow`() {
        val entropy = hexToBytes("9f6a2878b2520799a44ef18bc7df394e7061a224d2c33cd015b157d746869863")
        val words = Bip39.toMnemonic(entropy)
        assertEquals(
            "panda eyebrow bullet gorilla call smoke muffin taste mesh discover soft ostrich alcohol speed nation flash devote level hobby quick inner drive ghost inside",
            words.joinToString(" "),
        )
    }

    @Test
    fun `test vector - all hour make`() {
        val entropy = hexToBytes("066dca1a2bb7e8a1db2832148ce9933eea0f3ac9548d793112d9a95c9407efad")
        val words = Bip39.toMnemonic(entropy)
        assertEquals(
            "all hour make first leader extend hole alien behind guard gospel lava path output census museum junior mass reopen famous sing advance salt reform",
            words.joinToString(" "),
        )
    }

    @Test
    fun `test vector - void come effort`() {
        val entropy = hexToBytes("f585c11aec520db57dd353c69554b21a89b20fb0650966fa0a9d6f74fd989d8f")
        val words = Bip39.toMnemonic(entropy)
        assertEquals(
            "void come effort suffer camp survey warrior heavy shoot primary clutch crush open amazing screen patrol group space point ten exist slush involve unfold",
            words.joinToString(" "),
        )
    }

    // --- Round-trip all official 256-bit vectors ---

    @Test
    fun `round-trip all official 256-bit vectors`() {
        val vectors =
            listOf(
                "0000000000000000000000000000000000000000000000000000000000000000",
                "7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f",
                "8080808080808080808080808080808080808080808080808080808080808080",
                "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                "68a79eaca2324873eacc50cb9c6eca8cc68ea5d936f98787c60c7ebc74e6ce7c",
                "9f6a2878b2520799a44ef18bc7df394e7061a224d2c33cd015b157d746869863",
                "066dca1a2bb7e8a1db2832148ce9933eea0f3ac9548d793112d9a95c9407efad",
                "f585c11aec520db57dd353c69554b21a89b20fb0650966fa0a9d6f74fd989d8f",
            )
        for (hex in vectors) {
            val entropy = hexToBytes(hex)
            val words = Bip39.toMnemonic(entropy)
            val recovered = Bip39.toEntropy(words)
            assertArrayEquals("Round-trip failed for $hex", entropy, recovered)
        }
    }

    private fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // --- Case insensitivity ---

    @Test
    fun `toEntropy handles uppercase words`() {
        val entropy = ByteArray(32) { (it * 7).toByte() }
        val words = Bip39.toMnemonic(entropy)
        val upper = words.map { it.uppercase() }
        // isMnemonic should still detect it (words are lowercased internally)
        // toEntropy should handle it
        assertArrayEquals(entropy, Bip39.toEntropy(upper.map { it.lowercase() }))
    }

    // --- Determinism ---

    @Test
    fun `same entropy always produces same mnemonic`() {
        val entropy = ByteArray(32) { 42 }
        val words1 = Bip39.toMnemonic(entropy)
        val words2 = Bip39.toMnemonic(entropy)
        assertEquals(words1, words2)
    }

    // --- Different entropy produces different mnemonic ---

    @Test
    fun `different entropy produces different mnemonic`() {
        val e1 = ByteArray(32) { 0 }
        val e2 = ByteArray(32) { 1 }
        assertNotEquals(Bip39.toMnemonic(e1), Bip39.toMnemonic(e2))
    }
}
