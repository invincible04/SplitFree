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
import com.splitfree.domain.repository.SecureStorageKeyLostException
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
 * Each value uses an Android Keystore key; hardware backing depends on the device.
 * The Base64 (NO_WRAP) value contains a 12-byte IV followed by ciphertext and its GCM tag
 * in a plain SharedPreferences file.
 *
 * Failure handling is deliberately split into three classes:
 *
 * 1. **Transient / unknown** ([KeyStoreException], [ProviderException], [IllegalStateException],
 *    [UnrecoverableKeyException], other [GeneralSecurityException]s, [IOException]): the
 *    cause is not evidence of key loss. The error is wrapped as [SecureStorageException]
 *    rather than triggering a reset or being treated as a successful read/write.
 * 2. **Single-value corruption** ([AEADBadTagException], or an [IllegalArgumentException] from
 *    a truncated / non-Base64 blob): only that one entry is unreadable. Reads return the
 *    caller's default for that key; every other key is left intact.
 * 3. **Lost key** ([KeyPermanentlyInvalidatedException], or the alias missing / not a
 *    `SecretKeyEntry` while encrypted values still exist): every stored value is
 *    permanently unreadable. Automatic reset requires [resetOnCorruption]; otherwise
 *    [SecureStorageKeyLostException] lets the caller validate replacement data before
 *    explicitly requesting [resetAfterKeyLoss].
 *
 * Mutations check synchronous `commit()` and throw if it reports failure. SharedPreferences
 * may already have changed its in-memory values, so failure does not imply rollback.
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

    /** Resolved under [keyLock]; invalidated on key loss, explicit repair or unknown Keystore failures. */
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
     * @throws SecureStorageException for unknown Keystore failures, failed repair or non-resettable key loss
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

    /**
     * Rechecks key usability before discarding ciphertext. A usable key or an inconclusive
     * check refuses repair; a missing, wrong-type or permanently invalidated key permits it.
     * Empty stores are also eligible so an unusable alias cannot block future writes.
     */
    override fun resetAfterKeyLoss() {
        synchronized(keyLock) {
            cachedKey = null
            val usable = guardTransient("check key for '$keyAlias'") { keyIsUsable() }
            if (usable) {
                throw SecureStorageException(
                    "Refusing to reset '$keyAlias': its Keystore key is still usable, so nothing has been lost"
                )
            }
            Log.e(TAG, "Resetting '$keyAlias' after key loss: discarding ${prefs.all.size} unreadable value(s)")
            wipeKeyAndValues()
        }
    }

    /**
     * Only a missing, wrong-type or permanently invalidated key returns false.
     * Other failures propagate so an inconclusive check cannot authorize a reset.
     */
    private fun keyIsUsable(): Boolean {
        val ks = loadKeyStore()
        val entry = if (ks.containsAlias(keyAlias)) ks.getEntry(keyAlias, null) else null
        if (entry !is KeyStore.SecretKeyEntry) return false
        return try {
            Cipher.getInstance(TRANSFORMATION).init(Cipher.ENCRYPT_MODE, entry.secretKey)
            true
        } catch (_: KeyPermanentlyInvalidatedException) {
            false
        }
    }

    /**
     * Caller holds [keyLock]. Delete the alias before clearing values so an alias-deletion
     * failure leaves the ciphertext as evidence of key loss. If clearing reports failure,
     * disk state is uncertain, but any surviving ciphertext still has no usable alias.
     */
    // UseKtx: the KTX edit(commit = true) {} discards commit()'s boolean, which the reset path must check.
    @SuppressLint("UseKtx")
    private fun wipeKeyAndValues() {
        guardTransient("delete key for '$keyAlias'") {
            val ks = loadKeyStore()
            if (ks.containsAlias(keyAlias)) ks.deleteEntry(keyAlias)
        }
        if (!prefs.edit().clear().commit()) {
            throw SecureStorageException("Failed to clear '$keyAlias' storage during key reset")
        }
    }

    private fun writeValue(key: String, plaintext: String) {
        val encoded = guardTransient("encrypt '$key'") {
            try {
                encrypt(plaintext)
            } catch (_: KeyResetException) {
                // Retry once after confirmed key loss; key generation remains lazy.
                encrypt(plaintext)
            }
        }
        commitOrThrow("write '$key'") { it.putString(key, encoded) }
    }

    /** Checks the commit result; a reported failure does not undo SharedPreferences memory changes. */
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
     * Confirmed key loss: report [SecureStorageKeyLostException] unless automatic reset is enabled.
     * After a successful reset, [KeyResetException] lets reads return their default and writes
     * retry with a fresh key. Reset failures propagate without claiming repair completed.
     */
    private fun handleLostKey(cause: Exception): Nothing {
        synchronized(keyLock) {
            cachedKey = null
            if (!resetOnCorruption) {
                throw SecureStorageKeyLostException(
                    "Keystore key '$keyAlias' is unrecoverable and reset is disabled; stored values cannot be decrypted",
                    cause
                )
            }
            Log.e(
                TAG,
                "Keystore key '$keyAlias' is unrecoverable; wiping ${prefs.all.size} value(s) and regenerating",
                cause
            )
            wipeKeyAndValues()
            throw KeyResetException(cause)
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Wraps unknown Keystore failures without requesting a reset. [block] handles known
     * corruption and key loss first; this wrapper does not roll back work already performed.
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
