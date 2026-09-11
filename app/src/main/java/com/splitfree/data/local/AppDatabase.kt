package com.splitfree.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.splitfree.data.local.dao.EventDao
import com.splitfree.data.local.dao.GroupDao
import com.splitfree.data.local.dao.OutboxDao
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.data.local.entities.GroupEntity
import com.splitfree.data.local.entities.OutboxEntity

@Database(
    entities = [EventEntity::class, GroupEntity::class, OutboxEntity::class],
    version = 1,
    exportSchema = true
)
/**
 * Room database for SplitFree's local-first storage.
 *
 * Only the *content* of expense, settlement, correction, deletion and snapshot events is stored as
 * NIP-44 ciphertext. Everything else (group name and description, member pubkeys, relay URLs,
 * member display names, event tags and event metadata such as author, timestamps, type, expense UUID) is
 * stored in plaintext and protected solely by Android's file-based encryption of the app sandbox.
 * Group symmetric keys are stored separately in [KeystoreEncryptedStorage][com.splitfree.data.util.KeystoreEncryptedStorage].
 */
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao

    abstract fun groupDao(): GroupDao

    abstract fun outboxDao(): OutboxDao
}
