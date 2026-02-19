package com.splitfree.domain.crypto.nip

import java.security.MessageDigest

/**
 * BIP-39 mnemonic implementation.
 * Converts 32-byte entropy (private key) ↔ 24-word mnemonic.
 * Uses the standard English wordlist (2048 words) loaded from resources.
 *
 * Source: https://github.com/bitcoin/bips/blob/master/bip-0039/english.txt
 */
object Bip39 {
    /**
     * Convert 32 bytes of entropy to a 24-word mnemonic.
     * 256 bits entropy + 8 bits checksum = 264 bits = 24 × 11-bit indices.
     */
    fun toMnemonic(entropy: ByteArray): List<String> {
        require(entropy.size == 32) { "Entropy must be 32 bytes" }
        val checksum = sha256(entropy)
        val bits = toBitString(entropy) + toBitString(byteArrayOf(checksum[0]))
        return (0 until 24).map { i ->
            val index = bits.substring(i * 11, i * 11 + 11).toInt(2)
            WORDLIST[index]
        }
    }

    /**
     * Convert a mnemonic back to 32 bytes of entropy.
     * @throws IllegalArgumentException if word count, words, or checksum are invalid.
     */
    fun toEntropy(words: List<String>): ByteArray {
        require(words.size == 24) { "Mnemonic must be 24 words, got ${words.size}" }
        val bits =
            words.joinToString("") { word ->
                val index =
                    WORD_INDEX[word.lowercase()]
                        ?: throw IllegalArgumentException("Unknown word: $word")
                index.toString(2).padStart(11, '0')
            }
        val entropyBits = bits.substring(0, 256)
        val checksumBits = bits.substring(256, 264)
        val entropy =
            ByteArray(32) { i ->
                entropyBits.substring(i * 8, i * 8 + 8).toInt(2).toByte()
            }
        val expectedChecksum = toBitString(byteArrayOf(sha256(entropy)[0]))
        require(checksumBits == expectedChecksum) { "Invalid checksum" }
        return entropy
    }

    /** Check if a string looks like a 24-word BIP-39 mnemonic (the only size we support). */
    fun isMnemonic(input: String): Boolean {
        val w = input.trim().split("\\s+".toRegex())
        return w.size == 24 && w.all { it.lowercase() in WORD_INDEX }
    }

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    private fun toBitString(bytes: ByteArray): String = bytes.joinToString("") {
        (it.toInt() and 0xFF).toString(2).padStart(8, '0')
    }

    private val WORD_INDEX: Map<String, Int> by lazy {
        WORDLIST.withIndex().associate { (i, w) -> w to i }
    }

    /** BIP-39 English wordlist (2048 words), loaded from bundled resource file. */
    val WORDLIST: List<String> by lazy {
        Bip39::class.java.getResourceAsStream("/bip39-english.txt")
            ?.bufferedReader()
            ?.readLines()
            ?.filter { it.isNotBlank() }
            ?: error("BIP-39 wordlist resource not found")
    }
}
