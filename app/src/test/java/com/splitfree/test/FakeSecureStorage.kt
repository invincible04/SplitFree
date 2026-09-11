package com.splitfree.test

import com.splitfree.domain.repository.SecureStorage
import com.splitfree.domain.repository.SecureStorageException

/** In-memory [SecureStorage] for unit tests. */
class FakeSecureStorage : SecureStorage {
    private val map = mutableMapOf<String, String>()

    /**
     * When true, the next [putString]/[putLong] throws [SecureStorageException] (and resets the
     * flag) without storing anything. Simulates a failed Keystore encrypt or a `commit()` that
     * returned false.
     */
    var failNextPut: Boolean = false

    override fun getString(key: String, default: String?): String? = map[key] ?: default
    override fun putString(key: String, value: String) {
        if (failNextPut) {
            failNextPut = false
            throw SecureStorageException("Simulated secure storage write failure for '$key'")
        }
        map[key] = value
    }
    override fun getLong(key: String, default: Long): Long = map[key]?.toLongOrNull() ?: default
    override fun putLong(key: String, value: Long) = putString(key, value.toString())
    override fun contains(key: String): Boolean = map.containsKey(key)
    override fun canDecrypt(key: String): Boolean = contains(key)
    override fun remove(key: String) {
        map.remove(key)
    }
    override fun clear() {
        map.clear()
    }
}
