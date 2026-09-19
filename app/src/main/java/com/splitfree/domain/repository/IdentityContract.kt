package com.splitfree.domain.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * Whether this device holds a usable identity, and if not, why. Drives the first screen the user sees.
 */
enum class IdentityState {
    /** The stored private-key value can be decrypted. */
    READY,

    /** No active private-key entry exists. Onboarding may create or import an identity. */
    ABSENT,

    /**
     * Stored identity data could not be read reliably. Offer a retry; do not overwrite it or infer key loss.
     */
    UNAVAILABLE,

    /**
     * The wrapping key is lost or the identity blob is corrupt. Restore the identity from a recovery phrase,
     * or explicitly create a new identity. Key-loss repair discards unreadable storage before installation.
     */
    RECOVERY_REQUIRED
}

@Serializable
data class IdentitySwitchTarget(
    val id: String,
    val oldPubkey: String?,
    val newPubkey: String,
    val pendingPubkey: String? = null,
    val supersededSwitchId: String? = null
)

interface IdentityContract {
    /** @return true if the active private-key entry exists and can be decrypted */
    fun hasIdentity(): Boolean

    /** Classifies identity presence and read failures; see [IdentityState]. */
    fun identityState(): IdentityState

    /**
     * Initial identity availability, updated when this manager installs an active key.
     * Equal values may be conflated; this is not a key-change stream or continuous Keystore health check.
     */
    fun observeHasIdentity(): Flow<Boolean>

    /** Active public key, or null when unavailable; replacements emit even while presence stays true. */
    fun observeActivePublicKey(): StateFlow<String?>

    /**
     * @return 64-char hex-encoded secp256k1 public key derived from the stored private key, or an
     *   empty string if no identity exists
     */
    fun getPublicKeyHex(): String

    /**
     * Returns private key as hex string.
     * Prefer [getPrivateKeyBytes] for crypto operations; hex strings are immutable
     * and cannot be zeroed from memory.
     */
    fun getPrivateKeyHex(): String

    /** @return raw 32-byte private key (caller must zero after use) */
    fun getPrivateKeyBytes(): ByteArray

    /** @return raw 32-byte public key */
    fun getPublicKeyBytes(): ByteArray

    /**
     * Generates and persists a new active keypair. Keeps readable pending-successor state; confirmed
     * wrapping-key loss requires resetting unreadable storage first. Application callers reconcile journals
     * through IdentitySwitchCoordinator.
     * @return hex public key of the new identity
     */
    fun generateKeyPair(): String

    /**
     * Returns the existing pending keypair or creates one without replacing the active identity.
     * Promotion occurs through [commitPendingKeyPair].
     * @return hex public key of the pending key
     */
    fun generatePendingKeyPair(): String

    /** Promote the pending keypair to active and delete the old one. */
    fun commitPendingKeyPair()

    /** Complete promotion/cleanup again after any secure-storage write failed, validating the journal target. */
    fun finishPendingKeyPair(expectedPubkey: String)

    /** Discard a pending keypair only when publication is known to be impossible. */
    fun discardPendingKeyPair()

    /** @return true if there is an uncommitted pending keypair */
    fun hasPendingKeyPair(): Boolean

    /** @return 64-char hex public key of the pending keypair, or null */
    fun getPendingPublicKeyHex(): String?

    /** @return raw 32-byte pending private key, or null; caller must zero the returned bytes */
    fun getPendingPrivateKeyBytes(): ByteArray?

    /**
     * Record that a key revocation has started (epoch seconds, now). Called immediately after
     * [generatePendingKeyPair], so a pending key that carries a start time is one whose revocation
     * this device began; a pending key without one has unknown provenance.
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
     * Imports a hex private key or space-separated BIP-39 mnemonic, replacing the active identity.
     * Keeps readable pending-successor and revocation state. Application callers reconcile journals through
     * IdentitySwitchCoordinator.
     *
     * Input is validated before writing. Confirmed wrapping-key loss triggers [SecureStorage.resetAfterKeyLoss]
     * before installation; other storage failures do not authorize a reset. The installed key is read back.
     *
     * @throws IllegalArgumentException if input is invalid; nothing is written
     * @throws SecureStorageException if storage is unavailable or installation cannot be confirmed
     */
    fun importKey(input: String)

    /** Persists a replacement key and recovery target before changing the active identity. */
    fun stageIdentitySwitch(input: String? = null, supersededSwitchId: String? = null): IdentitySwitchTarget

    fun stagedIdentitySwitch(): IdentitySwitchTarget?

    /** Validates [target] against the staged key, then installs it; staged recovery data remains. */
    fun commitIdentitySwitch(target: IdentitySwitchTarget)

    /** Removes staged recovery data only after confirming the target identity is active. */
    fun completeIdentitySwitch(target: IdentitySwitchTarget)

    /** Saves the pending key and revocation tracking under [owner] before clearing the active pending slot. */
    fun archivePendingKeyPair(owner: String)

    fun restoreArchivedPendingKeyPair(owner: String, expectedPubkey: String? = null)

    fun getArchivedPendingPublicKeyHex(owner: String, expectedPubkey: String? = null): String?
}
