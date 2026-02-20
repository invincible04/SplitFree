package com.splitfree.data.util

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import com.splitfree.domain.repository.SecureStorage
import com.splitfree.util.DebugLog as Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM encrypted storage backed by Android Keystore.
 *
 * Each value is encrypted with a hardware-backed key. The 12-byte IV is prepended
 * to the ciphertext and the result is Base64-encoded into a plain SharedPreferences file.
 *
 * @param resetOnCorruption if true, key corruption wipes all data and recreates the key
 *   (suitable for re-syncable data like group keys). If false, the exception propagates
 *   (suitable for identity keys where the user must restore from BIP-39 mnemonic).
 */
class KeystoreEncryptedStorage(
    context: Context,
    prefsName: String,
    private val keyAlias: String,
    private val resetOnCorruption: Boolean
) : SecureStorage {

    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private fun getOrCreateKey(): KeyStore.Entry {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(keyAlias)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(
                        keyAlias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                generateKey()
            }
        }
        return ks.getEntry(keyAlias, null)
    }

    private fun encrypt(plaintext: String): String {
        val key = (getOrCreateKey() as KeyStore.SecretKeyEntry).secretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ct = cipher.doFinal(plaintext.toByteArray())
        val blob = cipher.iv + ct // 12-byte IV + ciphertext
        return Base64.encodeToString(blob, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val blob = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = blob.copyOfRange(0, 12)
        val ct = blob.copyOfRange(12, blob.size)
        val key = (getOrCreateKey() as KeyStore.SecretKeyEntry).secretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ct))
    }

    private fun <T> withCorruptionHandling(block: () -> T, fallback: T): T = try {
        block()
    } catch (e: KeyPermanentlyInvalidatedException) {
        handleCorruption(e)
        fallback
    } catch (e: java.security.KeyStoreException) {
        handleCorruption(e)
        fallback
    }

    private fun handleCorruption(e: Exception) {
        if (!resetOnCorruption) throw e
        Log.e("KeystoreEncryptedStorage", "Key corrupted for '$keyAlias', resetting: ${e.message}")
        try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)
        } catch (_: Exception) {}
        prefs.edit().clear().apply()
    }

    override fun getString(key: String, default: String?): String? = withCorruptionHandling({
        prefs.getString(key, null)?.let { decrypt(it) } ?: default
    }, default)

    override fun putString(key: String, value: String) =
        withCorruptionHandling({ prefs.edit().putString(key, encrypt(value)).apply() }, Unit)

    override fun getLong(key: String, default: Long): Long = withCorruptionHandling({
        prefs.getString(key, null)?.let { decrypt(it).toLongOrNull() } ?: default
    }, default)

    override fun putLong(key: String, value: Long) =
        withCorruptionHandling({ prefs.edit().putString(key, encrypt(value.toString())).apply() }, Unit)

    override fun contains(key: String): Boolean = withCorruptionHandling({ prefs.contains(key) }, false)

    override fun remove(key: String) = withCorruptionHandling({ prefs.edit().remove(key).apply() }, Unit)

    override fun clear() = withCorruptionHandling({ prefs.edit().clear().apply() }, Unit)
}
