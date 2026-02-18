package com.splitfree.data.util

import java.security.MessageDigest

/**
 * SHA-256 hashing for non-cryptographic use (export integrity, snapshot dedup).
 */
object HashUtil {
    fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
