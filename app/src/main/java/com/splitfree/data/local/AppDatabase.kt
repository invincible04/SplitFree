package com.splitfree.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.splitfree.data.local.dao.DeliveryDao
import com.splitfree.data.local.dao.EventDao
import com.splitfree.data.local.dao.GroupDao
import com.splitfree.data.local.dao.OutboxDao
import com.splitfree.data.local.entities.DeliveryEntity
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.data.local.entities.GroupEntity
import com.splitfree.data.local.entities.OutboxEntity

@Database(
    entities = [EventEntity::class, GroupEntity::class, OutboxEntity::class, DeliveryEntity::class],
    version = 2,
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
 *
 * Schema history:
 * - v1: `events`, `groups`, `outbox`
 * - v2: `events.applyState`, `groups.lastMetaEventId`, `groups.memberClocks`, new `deliveries`
 *   table ([MIGRATION_1_2], additive only)
 */
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao

    abstract fun groupDao(): GroupDao

    abstract fun outboxDao(): OutboxDao

    abstract fun deliveryDao(): DeliveryDao

    companion object {
        /**
         * 1 -> 2: adds the deferred-apply flag on events, the meta tiebreak / per-member clocks on
         * groups, and the `deliveries` table for nearby mesh envelope carriage. Purely additive;
         * every existing row keeps its data and picks up the column defaults.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN applyState INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `groups` ADD COLUMN lastMetaEventId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `groups` ADD COLUMN memberClocks TEXT NOT NULL DEFAULT '{}'")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS deliveries (" +
                        "envelopeId TEXT NOT NULL, groupId TEXT NOT NULL, recipient TEXT NOT NULL, eventId TEXT, " +
                        "envelopeJson TEXT, eventType TEXT NOT NULL, state INTEGER NOT NULL, " +
                        "source INTEGER NOT NULL, createdAt INTEGER NOT NULL, receivedAt INTEGER NOT NULL, " +
                        "sizeBytes INTEGER NOT NULL, PRIMARY KEY(envelopeId))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_deliveries_groupId_recipient ON deliveries(groupId, recipient)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_deliveries_groupId_state ON deliveries(groupId, state)")
            }
        }
    }
}
