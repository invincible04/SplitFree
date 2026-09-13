package com.splitfree.di

import android.content.Context
import androidx.room.Room
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.local.dao.ControlOperationDao
import com.splitfree.data.local.dao.DeliveryDao
import com.splitfree.data.local.dao.EventDao
import com.splitfree.data.local.dao.GroupDao
import com.splitfree.data.local.dao.OutboxDao
import com.splitfree.data.local.dao.SyncRevisionDao
import com.splitfree.data.repository.ControlOperationJournal
import com.splitfree.domain.repository.ControlOperationJournalContract
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
 * Currently at **version 3** (`exportSchema = true`). Registered migrations:
 * - [AppDatabase.MIGRATION_1_2]: additive columns on `events` / `groups` plus the `deliveries` table.
 * - [AppDatabase.MIGRATION_2_3]: control journal and persistent sync revisions (including change triggers).
 *
 * ### Do not use `fallbackToDestructiveMigration()`
 *
 * The app splits persistent state across two storage layers:
 * - **Room**: group metadata, encrypted Nostr events, the outbox queue and carried envelopes
 * - **[KeystoreEncryptedStorage][com.splitfree.data.util.KeystoreEncryptedStorage]**: group symmetric encryption keys
 *
 * Destructive migration wipes Room but leaves the Keystore intact, which
 * orphans the encryption keys. Without a populated `groups` table the app
 * has nothing to subscribe to on relays, cannot re-sync, and the user must
 * re-join every group via invite links or import a `.splitfree` backup.
 *
 * ### Adding a schema change
 *
 * - Bump `version` in [AppDatabase]
 * - Add a `Migration(N, N+1)` in [AppDatabase.Companion] and register it here
 * - Prefer `ALTER TABLE … ADD COLUMN` over destructive changes
 * - Cover it in `MigrationTest` (raw v(N) file -> Room v(N+1)) against the exported JSON schemas
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase = Room
        .databaseBuilder(context, AppDatabase::class.java, "splitfree.db")
        .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
        .addCallback(AppDatabase.SYNC_REVISION_CALLBACK)
        .build()

    @Provides fun provideEventDao(db: AppDatabase): EventDao = db.eventDao()

    @Provides fun provideGroupDao(db: AppDatabase): GroupDao = db.groupDao()

    @Provides fun provideOutboxDao(db: AppDatabase): OutboxDao = db.outboxDao()

    @Provides fun provideControlOperationDao(db: AppDatabase): ControlOperationDao = db.controlOperationDao()

    @Provides fun provideSyncRevisionDao(db: AppDatabase): SyncRevisionDao = db.syncRevisionDao()

    @Provides
    @Singleton
    fun provideControlOperationJournal(impl: ControlOperationJournal): ControlOperationJournalContract = impl

    @Provides fun provideDeliveryDao(db: AppDatabase): DeliveryDao = db.deliveryDao()
}
