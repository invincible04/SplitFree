package com.splitfree.test

import com.splitfree.domain.repository.SecureStorage
import com.splitfree.domain.repository.SecureStorageException
import com.splitfree.domain.repository.SecureStorageKeyLostException

/** In-memory [SecureStorage] for unit tests. */
class FakeSecureStorage : SecureStorage {
    private val map = mutableMapOf<String, String>()

    /**
     * The next put that passes the failure guards throws without storing, then clears this flag.
     * Models encryption or commit failure without exercising Android Keystore.
     */
    var failNextPut: Boolean = false

    /**
     * With stored values, existing-key reads and puts throw [SecureStorageKeyLostException].
     * Missing-key reads and contains/remove/clear bypass the guard. [resetAfterKeyLoss] clears
     * both values and this flag; an empty store does not trigger key-loss failures.
     */
    var keyLost: Boolean = false

    /** Existing-key reads, puts and reset throw this first; missing-key reads and removal bypass it. */
    var transientFailure: SecureStorageException? = null

    /** How many times [resetAfterKeyLoss] wiped the store. */
    var resets: Int = 0
        private set

    private fun guard() {
        transientFailure?.let { throw it }
        if (keyLost && map.isNotEmpty()) throw SecureStorageKeyLostException("Simulated lost Keystore key")
    }

    override fun getString(key: String, default: String?): String? {
        if (map.containsKey(key)) guard()
        return map[key] ?: default
    }

    override fun putString(key: String, value: String) {
        guard()
        if (failNextPut) {
            failNextPut = false
            throw SecureStorageException("Simulated secure storage write failure for '$key'")
        }
        map[key] = value
    }

    override fun getLong(key: String, default: Long): Long = getString(key, null)?.toLongOrNull() ?: default
    override fun putLong(key: String, value: Long) = putString(key, value.toString())
    override fun contains(key: String): Boolean = map.containsKey(key)
    override fun canDecrypt(key: String): Boolean = try {
        getString(key, null) != null
    } catch (_: Exception) {
        false
    }
    override fun remove(key: String) {
        map.remove(key)
    }
    override fun clear() {
        map.clear()
    }

    override fun resetAfterKeyLoss() {
        transientFailure?.let { throw it }
        if (!keyLost || map.isEmpty()) {
            throw SecureStorageException("Refusing to reset: the key is still usable, nothing has been lost")
        }
        map.clear()
        keyLost = false
        resets++
    }
}
