package com.splitfree.domain.util

import java.security.MessageDigest

/**
 * SHA-256 hashing for non-cryptographic use (export integrity, snapshot dedup).
 */
object HashUtil {
    /** @return lowercase hex-encoded SHA-256 digest of [input] */
    fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
