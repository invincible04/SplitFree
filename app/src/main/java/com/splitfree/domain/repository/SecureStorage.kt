package com.splitfree.domain.repository

/**
 * A secure-storage operation could not be confirmed, including read, encryption or commit failures.
 * Abort dependent work and reconcile before retrying: a failed write does not guarantee rollback.
 */
open class SecureStorageException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * The wrapping key is missing or permanently unusable and automatic reset is disabled.
 * Recovery requires explicit [SecureStorage.resetAfterKeyLoss] and replacement data, such as an identity
 * recovery phrase. A generic [SecureStorageException] alone is not evidence of key loss.
 */
class SecureStorageKeyLostException(message: String, cause: Throwable? = null) : SecureStorageException(message, cause)

/** Abstraction over encrypted key-value storage for testability. */
interface SecureStorage {
    /**
     * @return the decrypted value, or [default] if the key is absent or its stored blob
     *   is definitively corrupt (bad tag / truncated). Only that one key is affected.
     * @throws SecureStorageException on transient key-store failures or unrecoverable key loss
     */
    fun getString(key: String, default: String?): String?

    /**
     * Encrypts and synchronously commits [value].
     *
     * @throws SecureStorageException if encryption or durable commit cannot be confirmed.
     *   Callers must not assume a failed write preserved the previous value.
     */
    fun putString(key: String, value: String)

    /**
     * @return the decrypted value, or [default] if the key is absent, not a number, or its
     *   stored blob is definitively corrupt. Only that one key is affected.
     * @throws SecureStorageException on transient key-store failures or unrecoverable key loss
     */
    fun getLong(key: String, default: Long): Long

    /**
     * Encrypts and synchronously commits [value].
     *
     * @throws SecureStorageException if encryption fails or the write is not committed.
     */
    fun putLong(key: String, value: Long)

    /**
     * Cheap existence check against the underlying store only. Does NOT prove the value
     * can still be decrypted; use [canDecrypt] for that.
     */
    fun contains(key: String): Boolean

    /**
     * Attempts a real decrypt of [key]. Returns false if the key is absent or if decryption
     * fails for ANY reason (corrupt blob, lost key-store key, transient error). Never throws.
     */
    fun canDecrypt(key: String): Boolean

    /**
     * Synchronously removes [key].
     *
     * @throws SecureStorageException if the removal is not committed
     */
    fun remove(key: String)

    /**
     * Synchronously removes every entry.
     *
     * @throws SecureStorageException if the removal is not committed
     */
    fun clear()

    /**
     * Rechecks wrapping-key loss before deleting the unusable alias and clearing encrypted values.
     * The next write creates a fresh key. Call only after confirmed key loss and validated replacement data.
     *
     * A usable key or inconclusive check prevents deletion. Reset is not atomic: failure after alias deletion
     * can leave ciphertext behind for the next repair attempt.
     *
     * @throws SecureStorageException if loss cannot be confirmed or a reset step fails
     */
    fun resetAfterKeyLoss()
}
