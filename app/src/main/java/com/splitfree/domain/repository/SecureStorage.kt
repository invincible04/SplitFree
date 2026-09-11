package com.splitfree.domain.repository

/**
 * Thrown when a [SecureStorage] operation cannot be completed reliably: the backing
 * key store is unavailable (transient), the write could not be committed, or the
 * encryption key has been lost and the implementation is not allowed to reset.
 *
 * Callers must treat this as "the value was NOT persisted / could NOT be read" and
 * abort any dependent work (e.g. never insert a group row whose key failed to store).
 */
class SecureStorageException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

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
     * @throws SecureStorageException if encryption fails or the write is not committed.
     *   When this is thrown the previous value (if any) is unchanged.
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
}
