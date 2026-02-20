package com.splitfree.domain.repository

/** Abstraction over encrypted key-value storage for testability. */
interface SecureStorage {
    fun getString(key: String, default: String?): String?
    fun putString(key: String, value: String)
    fun getLong(key: String, default: Long): Long
    fun putLong(key: String, value: Long)
    fun contains(key: String): Boolean
    fun remove(key: String)
    fun clear()
}
