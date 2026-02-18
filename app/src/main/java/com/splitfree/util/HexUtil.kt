package com.splitfree.util

/**
 * Hex encoding/decoding extensions used throughout the codebase for Nostr keys and event IDs.
 */

/**
 * @return lowercase hex string representation of this byte array
 */
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/**
 * @return byte array decoded from this hex string
 * @throws IllegalArgumentException if the string has odd length
 */
fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex string must have even length" }
    return ByteArray(length / 2) { i ->
        ((Character.digit(this[i * 2], 16) shl 4) + Character.digit(this[i * 2 + 1], 16)).toByte()
    }
}
