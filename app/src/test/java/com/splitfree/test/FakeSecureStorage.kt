package com.splitfree.test

import com.splitfree.domain.repository.SecureStorage

/** In-memory [SecureStorage] for unit tests. */
class FakeSecureStorage : SecureStorage {
    private val map = mutableMapOf<String, String>()

    override fun getString(key: String, default: String?): String? = map[key] ?: default
    override fun putString(key: String, value: String) {
        map[key] = value
    }
    override fun getLong(key: String, default: Long): Long = map[key]?.toLongOrNull() ?: default
    override fun putLong(key: String, value: Long) {
        map[key] = value.toString()
    }
    override fun contains(key: String): Boolean = map.containsKey(key)
    override fun remove(key: String) {
        map.remove(key)
    }
    override fun clear() {
        map.clear()
    }
}
