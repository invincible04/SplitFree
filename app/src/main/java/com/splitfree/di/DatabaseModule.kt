package com.splitfree.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.local.dao.EventDao
import com.splitfree.data.local.dao.GroupDao
import com.splitfree.data.local.dao.OutboxDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the Room [AppDatabase] singleton and DAO bindings.
 *
 * ## Schema migration
 *
 * Currently at **version 1** (`exportSchema = true`).
 *
 * ### Do not use `fallbackToDestructiveMigration()`
 *
 * The app splits persistent state across two storage layers:
 * - **Room** — group metadata, encrypted Nostr events, and the outbox queue
 * - **[KeystoreEncryptedStorage][com.splitfree.data.util.KeystoreEncryptedStorage]** — group symmetric encryption keys
 *
 * Destructive migration wipes Room but leaves the Keystore intact, which
 * orphans the encryption keys. Without a populated `groups` table the app
 * has nothing to subscribe to on relays, cannot re-sync, and the user must
 * re-join every group via invite links or import a `.splitfree` backup.
 *
 * ### Adding a schema change
 *
 * - Bump `version` in [AppDatabase]
 * - Add a `Migration(N, N+1)` to [MIGRATIONS]
 * - Prefer `ALTER TABLE … ADD COLUMN` over destructive changes
 * - Validate with `MigrationTestHelper` against the exported JSON schemas
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /** Ordered list of Room migrations applied at database open. */
    internal val MIGRATIONS: Array<Migration> = arrayOf()

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase = Room
        .databaseBuilder(context, AppDatabase::class.java, "splitfree.db")
        .addMigrations(*MIGRATIONS)
        .build()

    @Provides fun provideEventDao(db: AppDatabase): EventDao = db.eventDao()

    @Provides fun provideGroupDao(db: AppDatabase): GroupDao = db.groupDao()

    @Provides fun provideOutboxDao(db: AppDatabase): OutboxDao = db.outboxDao()
}
