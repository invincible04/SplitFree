package com.splitfree.domain.crypto

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import rust.nostr.sdk.Keys
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the user's secp256k1 Schnorr keypair via rust-nostr SDK.
 * Keys stored in EncryptedSharedPreferences (hardware-backed on supported devices).
 */
@Singleton
class IdentityManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "splitfree_identity",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private var cachedKeys: Keys? = null

    fun hasIdentity(): Boolean = prefs.contains(KEY_PRIVATE)

    fun getPublicKey(): String = prefs.getString(KEY_PUBLIC, "")!!

    fun getPrivateKey(): String = prefs.getString(KEY_PRIVATE, "")!!

    fun getKeys(): Keys {
        cachedKeys?.let { return it }
        val keys = Keys(SecretKey.fromHex(getPrivateKey()))
        cachedKeys = keys
        return keys
    }

    fun generateKeyPair(): Pair<String, String> {
        val keys = Keys.generate()
        val privHex = keys.secretKey().toHex()
        val pubHex = keys.publicKey().toHex()
        prefs.edit()
            .putString(KEY_PRIVATE, privHex)
            .putString(KEY_PUBLIC, pubHex)
            .apply()
        cachedKeys = keys
        return privHex to pubHex
    }

    /**
     * Import a key from hex, nsec bech32, or 12/24-word BIP-39 mnemonic.
     * @throws Exception if the input is not a valid key format.
     */
    fun importKey(input: String) {
        val keys = Keys.parse(input.trim())
        val privHex = keys.secretKey().toHex()
        val pubHex = keys.publicKey().toHex()
        prefs.edit()
            .putString(KEY_PRIVATE, privHex)
            .putString(KEY_PUBLIC, pubHex)
            .apply()
        cachedKeys = keys
    }

    /** Returns the nsec (bech32) representation for display/backup. */
    fun getNsec(): String = getKeys().secretKey().toBech32()

    /** Returns the npub (bech32) representation for sharing. */
    fun getNpub(): String = getKeys().publicKey().toBech32()

    companion object {
        private const val KEY_PRIVATE = "nsec"
        private const val KEY_PUBLIC = "npub"
    }
}
