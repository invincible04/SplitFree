package com.splitfree.domain.util

import java.security.MessageDigest

/**
 * SHA-256 hashing for non-cryptographic use (export integrity, snapshot dedup).
 */
object HashUtil {
    /**
     * Number of hex characters kept by [eventHashPrefix] (96 bits). Collision-safe for
     * deduplicating event IDs at any realistic group size while keeping snapshots small.
     */
    const val EVENT_HASH_PREFIX_LENGTH = 24

    /** @return lowercase hex-encoded SHA-256 digest of [input] */
    fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Truncated event hash stored in a balance snapshot's `event_hashes`.
     *
     * @return the first [EVENT_HASH_PREFIX_LENGTH] hex characters of `sha256Hex(id)`
     */
    fun eventHashPrefix(id: String): String = sha256Hex(id).take(EVENT_HASH_PREFIX_LENGTH)
}
