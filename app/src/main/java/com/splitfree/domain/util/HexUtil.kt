package com.splitfree.domain.util

/**
 * Hex encoding/decoding extensions for Nostr keys and event IDs.
 */

/** @return lowercase hex string representation of this byte array */
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/**
 * @return byte array decoded from this hex string (either case accepted)
 * @throws IllegalArgumentException if the string has odd length or contains a non-hex character
 */
fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex string must have even length" }
    return ByteArray(length / 2) { i ->
        val hi = Character.digit(this[i * 2], 16)
        val lo = Character.digit(this[i * 2 + 1], 16)
        require(hi >= 0 && lo >= 0) { "invalid hex character" }
        ((hi shl 4) + lo).toByte()
    }
}
