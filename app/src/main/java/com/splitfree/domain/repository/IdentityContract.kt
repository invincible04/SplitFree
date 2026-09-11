package com.splitfree.domain.repository

/**
 * Domain contract for cryptographic identity management.
 * Abstracts key storage so the domain layer has no Android dependency.
 */
interface IdentityContract {
    /** @return true if a keypair has been generated or imported */
    fun hasIdentity(): Boolean

    /** @return 64-char hex-encoded secp256k1 public key */
    fun getPublicKeyHex(): String

    /**
     * Returns private key as hex string.
     * Prefer [getPrivateKeyBytes] for crypto operations — hex strings are immutable
     * and cannot be zeroed from memory.
     */
    fun getPrivateKeyHex(): String

    /** @return raw 32-byte private key (caller must zero after use) */
    fun getPrivateKeyBytes(): ByteArray

    /** @return raw 32-byte public key */
    fun getPublicKeyBytes(): ByteArray

    /** Generate and persist a new secp256k1 keypair. @return (privHex, pubHex) */
    fun generateKeyPair(): Pair<String, String>

    /**
     * Generate a pending keypair for key revocation.
     * The current key is NOT overwritten until [commitPendingKeyPair].
     * @return (privHex, pubHex) of the pending key
     */
    fun generatePendingKeyPair(): Pair<String, String>

    /** Promote the pending keypair to active and delete the old one. */
    fun commitPendingKeyPair()

    /** Discard a pending keypair (e.g. on revocation failure). */
    fun discardPendingKeyPair()

    /** @return true if there is an uncommitted pending keypair */
    fun hasPendingKeyPair(): Boolean

    /** @return 64-char hex public key of the pending keypair, or null */
    fun getPendingPublicKeyHex(): String?

    /** @return raw 32-byte pending private key, or null */
    fun getPendingPrivateKeyBytes(): ByteArray?

    /**
     * Record that a key revocation has started (epoch seconds, now). Called immediately after
     * [generatePendingKeyPair] so an interrupted revocation can be told apart from a pending
     * key left behind by an older build that never recorded a start time.
     */
    fun markRevocationStarted()

    /** Store event IDs created during key revocation so resumeIfNeeded can track them. */
    fun setRevocationEventIds(eventIds: List<String>)

    /** @return event IDs from the in-progress revocation, or empty */
    fun getRevocationEventIds(): List<String>

    /** @return epoch seconds when the revocation was started (see [markRevocationStarted]), or 0 */
    fun getRevocationStartTime(): Long

    /** Export private key as a 24-word BIP-39 mnemonic. */
    fun exportAsMnemonic(): List<String>

    /**
     * Import a key from hex string or BIP-39 mnemonic (space-separated words).
     * @throws IllegalArgumentException if the key is invalid
     */
    fun importKey(input: String)
}
