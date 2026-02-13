package com.splitfree.domain.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.acinq.secp256k1.Secp256k1
import java.io.File
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the user's secp256k1 keypair using ACINQ secp256k1-kmp (no SDK).
 * Keys stored in EncryptedSharedPreferences.
 */
@Singleton
class IdentityManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        try {
            createEncryptedPrefs()
        } catch (e: Exception) {
            // Keystore master key invalidated (biometric change, backup restore, OS upgrade).
            // Delete corrupted prefs file and recreate — user must recover via seed phrase.
            Log.e("IdentityManager", "EncryptedSharedPreferences failed, resetting: ${e.message}")
            try {
                File(context.filesDir.parent, "shared_prefs/splitfree_identity.xml").delete()
            } catch (_: Exception) {}
            createEncryptedPrefs()
        }
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            "splitfree_identity",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun hasIdentity(): Boolean = prefs.contains(KEY_PRIVATE)

    fun getPublicKeyHex(): String = prefs.getString(KEY_PUBLIC, "")!!

    /**
     * Returns private key as hex string. WARNING: String is immutable and cannot be
     * zeroed from memory. Use getPrivateKeyBytes() + fill(0) for crypto operations.
     * Only use this for user-facing display (Settings screen reveal).
     */
    fun getPrivateKeyHex(): String = prefs.getString(KEY_PRIVATE, "")!!

    fun getPrivateKeyBytes(): ByteArray = getPrivateKeyHex().hexToBytes()

    fun getPublicKeyBytes(): ByteArray = getPublicKeyHex().hexToBytes()

    fun generateKeyPair(): Pair<String, String> {
        val privKey = ByteArray(32)
        val random = SecureRandom()
        do {
            random.nextBytes(privKey)
        } while (!Secp256k1.secKeyVerify(privKey))

        val pubHex = NostrEvent.pubkeyFromPrivkey(privKey)
        val privHex = privKey.toHex()
        privKey.fill(0)

        prefs.edit()
            .putString(KEY_PRIVATE, privHex)
            .putString(KEY_PUBLIC, pubHex)
            .remove(KEY_PENDING_PRIVATE)
            .remove(KEY_PENDING_PUBLIC)
            .apply()
        return privHex to pubHex
    }

    /**
     * Generate a new keypair and store it as "pending" alongside the current one.
     * The old key is NOT overwritten until [commitPendingKeyPair] is called.
     */
    fun generatePendingKeyPair(): Pair<String, String> {
        val privKey = ByteArray(32)
        val random = SecureRandom()
        do {
            random.nextBytes(privKey)
        } while (!Secp256k1.secKeyVerify(privKey))

        val pubHex = NostrEvent.pubkeyFromPrivkey(privKey)
        val privHex = privKey.toHex()
        privKey.fill(0)

        prefs.edit()
            .putString(KEY_PENDING_PRIVATE, privHex)
            .putString(KEY_PENDING_PUBLIC, pubHex)
            .apply()
        return privHex to pubHex
    }

    /** Promote the pending keypair to active and delete the old one. */
    fun commitPendingKeyPair() {
        val pendingPriv = prefs.getString(KEY_PENDING_PRIVATE, null)
            ?: error("No pending keypair to commit")
        val pendingPub = prefs.getString(KEY_PENDING_PUBLIC, null)
            ?: error("No pending keypair to commit")
        prefs.edit()
            .putString(KEY_PRIVATE, pendingPriv)
            .putString(KEY_PUBLIC, pendingPub)
            .remove(KEY_PENDING_PRIVATE)
            .remove(KEY_PENDING_PUBLIC)
            .apply()
    }

    /** Discard a pending keypair (e.g., on revocation failure). */
    fun discardPendingKeyPair() {
        prefs.edit()
            .remove(KEY_PENDING_PRIVATE)
            .remove(KEY_PENDING_PUBLIC)
            .apply()
    }

    /** Check if there's an incomplete revocation to resume. */
    fun hasPendingKeyPair(): Boolean = prefs.contains(KEY_PENDING_PRIVATE)

    fun getPendingPublicKeyHex(): String? = prefs.getString(KEY_PENDING_PUBLIC, null)

    fun getPendingPrivateKeyBytes(): ByteArray? =
        prefs.getString(KEY_PENDING_PRIVATE, null)?.hexToBytes()

    /**
     * Export private key as 24-word BIP-39 mnemonic.
     */
    fun exportAsMnemonic(): List<String> {
        val privBytes = getPrivateKeyBytes()
        try {
            return Bip39.toMnemonic(privBytes)
        } finally {
            privBytes.fill(0)
        }
    }

    /**
     * Import a key from hex string, or BIP-39 mnemonic (space-separated words).
     * @throws IllegalArgumentException if the key is invalid.
     */
    fun importKey(input: String) {
        val trimmed = input.trim()
        val words = trimmed.split("\\s+".toRegex())
        val privBytes = if (Bip39.isMnemonic(trimmed)) {
            require(words.size == 24) { "Only 24-word seed phrases are supported (got ${words.size})" }
            Bip39.toEntropy(words)
        } else {
            trimmed.hexToBytes()
        }
        require(privBytes.size == 32 && Secp256k1.secKeyVerify(privBytes)) { "Invalid private key" }

        val pubHex = NostrEvent.pubkeyFromPrivkey(privBytes)
        val privHex = privBytes.toHex()
        prefs.edit()
            .putString(KEY_PRIVATE, privHex)
            .putString(KEY_PUBLIC, pubHex)
            .apply()
        privBytes.fill(0)
    }

    companion object {
        private const val KEY_PRIVATE = "nsec"
        private const val KEY_PUBLIC = "npub"
        private const val KEY_PENDING_PRIVATE = "nsec_pending"
        private const val KEY_PENDING_PUBLIC = "npub_pending"
    }
}
