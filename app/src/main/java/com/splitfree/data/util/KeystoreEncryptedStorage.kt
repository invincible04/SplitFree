package com.splitfree.data.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import com.splitfree.domain.repository.SecureStorage
import com.splitfree.domain.repository.SecureStorageException
import com.splitfree.util.DebugLog as Log
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM encrypted storage backed by Android Keystore.
 *
 * Each value is encrypted with a hardware-backed key. The 12-byte IV is prepended
 * to the ciphertext and the result is Base64 (NO_WRAP) encoded into a plain
 * SharedPreferences file. The on-disk format is unchanged from earlier versions.
 *
 * Failure handling is deliberately split into three classes:
 *
 * 1. **Transient / unknown** ([KeyStoreException], [ProviderException], [IllegalStateException],
 *    [UnrecoverableKeyException], other [GeneralSecurityException]s, [IOException]): the
 *    Keystore daemon hiccuped or is temporarily unavailable. Nothing on disk is touched; the
 *    error is rethrown as [SecureStorageException] so callers abort instead of proceeding as
 *    if the write/read had succeeded.
 * 2. **Single-value corruption** ([AEADBadTagException], or an [IllegalArgumentException] from
 *    a truncated / non-Base64 blob): only that one entry is unreadable. Reads return the
 *    caller's default for that key; every other key is left intact.
 * 3. **Lost key** ([KeyPermanentlyInvalidatedException], or the alias missing / not a
 *    `SecretKeyEntry` while encrypted values still exist): every stored value is
 *    permanently unreadable. This is the *only* case that resets the store, and only when
 *    [resetOnCorruption] is true; otherwise a [SecureStorageException] is thrown.
 *
 * All mutations use synchronous `commit()` and throw [SecureStorageException] if the write
 * does not land, so a caller never continues past a key write that silently failed.
 *
 * @param resetOnCorruption if true, an unrecoverable Keystore key wipes all data and a new
 *   key is generated lazily (suitable for re-syncable data like group keys). If false, the
 *   failure propagates (suitable for identity keys the user must restore from a mnemonic).
 */
class KeystoreEncryptedStorage(
    context: Context,
    prefsName: String,
    private val keyAlias: String,
    private val resetOnCorruption: Boolean
) : SecureStorage {

    private val prefs: SharedPreferences = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private val keyLock = Any()

    /** Resolved Keystore key. Set once under [keyLock]; cleared by [handleLostKey] and on transient errors. */
    @Volatile
    private var cachedKey: SecretKey? = null

    /**
     * Internal signal that [handleLostKey] wiped the store (reset mode). Reads translate it into
     * "return default"; writes retry once with the freshly generated key. Never escapes this class.
     */
    private class KeyResetException(cause: Exception) : RuntimeException(cause)

    // ------------------------------------------------------------------ reads

    override fun getString(key: String, default: String?): String? {
        val encoded = prefs.getString(key, null) ?: return default
        return readValue(key, encoded) ?: default
    }

    override fun getLong(key: String, default: Long): Long {
        val encoded = prefs.getString(key, null) ?: return default
        return readValue(key, encoded)?.toLongOrNull() ?: default
    }

    /** Cheap presence check only. See [canDecrypt] for a real recoverability check. */
    override fun contains(key: String): Boolean = prefs.contains(key)

    override fun canDecrypt(key: String): Boolean = try {
        getString(key, null) != null
    } catch (e: Exception) {
        Log.w(TAG, "canDecrypt('$key') failed for '$keyAlias'", e)
        false
    }

    /**
     * Decrypts a single stored blob.
     *
     * @return the plaintext, or null if THIS blob is definitively corrupt or the store was
     *   just reset. Other keys are never affected by a corrupt neighbour.
     * @throws SecureStorageException for transient failures or non-resettable key loss
     */
    private fun readValue(key: String, encoded: String): String? = guardTransient("read '$key'") {
        try {
            decrypt(encoded)
        } catch (e: AEADBadTagException) {
            Log.w(TAG, "Corrupt ciphertext for '$key' in '$keyAlias' (bad auth tag); returning default", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Corrupt ciphertext for '$key' in '$keyAlias' (${e.message}); returning default", e)
            null
        } catch (_: KeyResetException) {
            // Store was wiped because the Keystore key is gone; the value no longer exists.
            null
        }
    }

    // ----------------------------------------------------------------- writes

    override fun putString(key: String, value: String) = writeValue(key, value)

    override fun putLong(key: String, value: Long) = writeValue(key, value.toString())

    override fun remove(key: String) = commitOrThrow("remove '$key'") { it.remove(key) }

    override fun clear() = commitOrThrow("clear") { it.clear() }

    private fun writeValue(key: String, plaintext: String) {
        val encoded = guardTransient("encrypt '$key'") {
            try {
                encrypt(plaintext)
            } catch (_: KeyResetException) {
                // The old key was unrecoverable and the store has been wiped. A fresh key is
                // generated lazily on this retry; if that fails too it surfaces as a
                // SecureStorageException rather than a silent no-op.
                encrypt(plaintext)
            }
        }
        commitOrThrow("write '$key'") { it.putString(key, encoded) }
    }

    /** Applies [edit] with a synchronous commit and throws if the write did not land. */
    // UseKtx: the KTX edit(commit = true) {} discards commit()'s boolean, which this class must check.
    @SuppressLint("UseKtx")
    private fun commitOrThrow(op: String, edit: (SharedPreferences.Editor) -> SharedPreferences.Editor) {
        if (!edit(prefs.edit()).commit()) {
            throw SecureStorageException("Failed to $op in '$keyAlias': SharedPreferences commit() returned false")
        }
    }

    // ----------------------------------------------------------------- crypto

    private fun encrypt(plaintext: String): String {
        val cipher = initCipher(Cipher.ENCRYPT_MODE, null)
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        check(iv.size == IV_LENGTH_BYTES) { "Unexpected GCM IV length ${iv.size}" }
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP) // 12-byte IV + ciphertext
    }

    private fun decrypt(encoded: String): String {
        // Base64.decode throws IllegalArgumentException on malformed input.
        val blob = Base64.decode(encoded, Base64.NO_WRAP)
        require(blob.size >= IV_LENGTH_BYTES + TAG_LENGTH_BYTES) {
            "ciphertext blob too short (${blob.size} bytes)"
        }
        val iv = blob.copyOfRange(0, IV_LENGTH_BYTES)
        val ct = blob.copyOfRange(IV_LENGTH_BYTES, blob.size)
        val cipher = initCipher(Cipher.DECRYPT_MODE, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    private fun initCipher(mode: Int, spec: GCMParameterSpec?): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            if (spec == null) cipher.init(mode, getOrCreateKey()) else cipher.init(mode, getOrCreateKey(), spec)
        } catch (e: KeyPermanentlyInvalidatedException) {
            handleLostKey(e)
        }
        return cipher
    }

    // -------------------------------------------------------- key management

    /**
     * Returns the cached Keystore key, resolving (or generating) it on first use.
     *
     * Double-checked under [keyLock] so concurrent first callers cannot race to generate two
     * keys, and cached in a volatile field so steady-state encrypt/decrypt skip the three
     * Keystore IPCs (load / containsAlias / getEntry).
     */
    private fun getOrCreateKey(): SecretKey {
        cachedKey?.let { return it }
        synchronized(keyLock) {
            cachedKey?.let { return it }
            return resolveKey().also { cachedKey = it }
        }
    }

    private fun resolveKey(): SecretKey {
        val ks = loadKeyStore()
        val entry = if (ks.containsAlias(keyAlias)) ks.getEntry(keyAlias, null) else null
        if (entry is KeyStore.SecretKeyEntry) return entry.secretKey

        if (prefs.all.isNotEmpty()) {
            // Ciphertext exists but the key that produced it is gone: nothing on disk is readable.
            val what = if (entry == null) "missing" else "not a SecretKeyEntry (${entry.javaClass.simpleName})"
            handleLostKey(
                KeyStoreException("Keystore alias '$keyAlias' is $what but ${prefs.all.size} encrypted value(s) exist")
            )
        }
        if (entry != null) {
            // Wrong entry type with no data behind it: discard it and start fresh.
            ks.deleteEntry(keyAlias)
        }
        return generateKey()
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /**
     * The Keystore key is definitively unrecoverable. This is the ONLY path that wipes data.
     *
     * Always throws: [SecureStorageException] when [resetOnCorruption] is false, otherwise
     * [KeyResetException] after deleting the alias and clearing the preferences so the next
     * [getOrCreateKey] call generates a fresh key lazily.
     */
    // UseKtx: the KTX edit(commit = true) {} discards commit()'s boolean, which the reset path must check.
    @SuppressLint("UseKtx")
    private fun handleLostKey(cause: Exception): Nothing {
        synchronized(keyLock) {
            cachedKey = null
            if (!resetOnCorruption) {
                throw SecureStorageException(
                    "Keystore key '$keyAlias' is unrecoverable and reset is disabled; stored values cannot be decrypted",
                    cause
                )
            }
            Log.e(
                TAG,
                "Keystore key '$keyAlias' is unrecoverable; wiping ${prefs.all.size} value(s) and regenerating",
                cause
            )
            try {
                loadKeyStore().deleteEntry(keyAlias)
            } catch (e: Exception) {
                Log.w(TAG, "Could not delete Keystore alias '$keyAlias' during reset", e)
            }
            if (!prefs.edit().clear().commit()) {
                throw SecureStorageException("Failed to clear '$keyAlias' storage during key reset", cause)
            }
            throw KeyResetException(cause)
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Converts transient / unknown Keystore failures into [SecureStorageException] WITHOUT
     * touching stored data. Single-value corruption and lost-key handling happen inside
     * [block], before this wrapper sees anything.
     */
    private inline fun <T> guardTransient(op: String, block: () -> T): T = try {
        block()
    } catch (e: KeyStoreException) {
        throw transient(op, e)
    } catch (e: UnrecoverableKeyException) {
        throw transient(op, e)
    } catch (e: ProviderException) {
        throw transient(op, e)
    } catch (e: IllegalStateException) {
        throw transient(op, e)
    } catch (e: GeneralSecurityException) {
        throw transient(op, e)
    } catch (e: IOException) {
        throw transient(op, e)
    }

    private fun transient(op: String, cause: Exception): SecureStorageException {
        // Drop the cached key so the next call re-resolves against the Keystore.
        cachedKey = null
        Log.w(TAG, "Transient Keystore failure during $op for '$keyAlias' (data untouched)", cause)
        return SecureStorageException("Secure storage '$keyAlias' unavailable during $op: ${cause.message}", cause)
    }

    companion object {
        private const val TAG = "KeystoreEncryptedStorage"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH_BYTES = 12
        private const val TAG_LENGTH_BITS = 128
        private const val TAG_LENGTH_BYTES = TAG_LENGTH_BITS / 8
    }
}
