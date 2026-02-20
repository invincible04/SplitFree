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
 * All expense data is stored encrypted — the database holds ciphertext, never plaintext.
 * Group symmetric keys are stored separately in [KeystoreEncryptedStorage][com.splitfree.data.util.KeystoreEncryptedStorage].
 */
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao

    abstract fun groupDao(): GroupDao

    abstract fun outboxDao(): OutboxDao
}
