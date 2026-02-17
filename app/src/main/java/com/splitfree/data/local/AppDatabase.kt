package com.splitfree.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.data.local.entities.GroupEntity
import com.splitfree.data.local.entities.OutboxEntity

@Database(
    entities = [EventEntity::class, GroupEntity::class, OutboxEntity::class],
    version = 5,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao

    abstract fun groupDao(): GroupDao

    abstract fun outboxDao(): OutboxDao

    companion object {
        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE events ADD COLUMN originalEventJson TEXT DEFAULT NULL")
                }
            }

        /** Clear any plaintext group keys still lingering in Room from pre-migration installs. */
        val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("UPDATE `groups` SET groupKey = '' WHERE groupKey != ''")
                }
            }

        val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `groups` ADD COLUMN lastMetaTimestamp INTEGER NOT NULL DEFAULT 0")
                }
            }

        /**
         * Security migration: drop contentDecrypted from events and groupKey from groups.
         * - contentDecrypted stored plaintext financial data in SQLite (CWE-312).
         *   Now decrypted on-the-fly from contentEncrypted using the group key.
         * - groupKey column was already blanked in migration 2→3 but the column
         *   remained, risking recovery from WAL/journal pages.
         *
         * SQLite < 3.35.0 (Android < 14) doesn't support ALTER TABLE DROP COLUMN,
         * so we recreate both tables.
         */
        val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // --- Recreate events table without contentDecrypted ---
                    db.execSQL(
                        """CREATE TABLE IF NOT EXISTS events_new (
                            eventId TEXT NOT NULL PRIMARY KEY,
                            groupId TEXT NOT NULL,
                            pubkey TEXT NOT NULL,
                            createdAt INTEGER NOT NULL,
                            kind INTEGER NOT NULL,
                            contentEncrypted TEXT NOT NULL,
                            eventType TEXT NOT NULL,
                            expenseUuid TEXT,
                            sig TEXT NOT NULL,
                            syncedToRelays TEXT NOT NULL DEFAULT '[]',
                            receivedAt INTEGER NOT NULL,
                            originalEventJson TEXT
                        )""",
                    )
                    db.execSQL(
                        """INSERT INTO events_new (eventId, groupId, pubkey, createdAt, kind,
                            contentEncrypted, eventType, expenseUuid, sig, syncedToRelays,
                            receivedAt, originalEventJson)
                           SELECT eventId, groupId, pubkey, createdAt, kind,
                            contentEncrypted, eventType, expenseUuid, sig, syncedToRelays,
                            receivedAt, originalEventJson
                           FROM events""",
                    )
                    db.execSQL("DROP TABLE events")
                    db.execSQL("ALTER TABLE events_new RENAME TO events")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_events_groupId_createdAt ON events (groupId, createdAt)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_events_groupId_eventType ON events (groupId, eventType)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_events_expenseUuid ON events (expenseUuid)")

                    // --- Recreate groups table without groupKey ---
                    db.execSQL(
                        """CREATE TABLE IF NOT EXISTS groups_new (
                            groupId TEXT NOT NULL PRIMARY KEY,
                            name TEXT NOT NULL,
                            description TEXT NOT NULL DEFAULT '',
                            createdBy TEXT NOT NULL,
                            createdAt INTEGER NOT NULL,
                            members TEXT NOT NULL,
                            relays TEXT NOT NULL,
                            lastSyncTimestamp INTEGER NOT NULL DEFAULT 0,
                            lastMetaTimestamp INTEGER NOT NULL DEFAULT 0
                        )""",
                    )
                    db.execSQL(
                        """INSERT INTO groups_new (groupId, name, description, createdBy, createdAt,
                            members, relays, lastSyncTimestamp, lastMetaTimestamp)
                           SELECT groupId, name, description, createdBy, createdAt,
                            members, relays, lastSyncTimestamp, lastMetaTimestamp
                           FROM `groups`""",
                    )
                    db.execSQL("DROP TABLE `groups`")
                    db.execSQL("ALTER TABLE groups_new RENAME TO `groups`")

                    // VACUUM to reclaim space and ensure old plaintext data is
                    // physically removed from the database file and WAL.
                    db.execSQL("VACUUM")
                }
            }
    }
}
