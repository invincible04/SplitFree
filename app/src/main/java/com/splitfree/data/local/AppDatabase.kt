package com.splitfree.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.splitfree.data.local.dao.ControlOperationDao
import com.splitfree.data.local.dao.DeliveryDao
import com.splitfree.data.local.dao.EventDao
import com.splitfree.data.local.dao.GroupDao
import com.splitfree.data.local.dao.OutboxDao
import com.splitfree.data.local.dao.RelaySyncCursorDao
import com.splitfree.data.local.dao.SyncRevisionDao
import com.splitfree.data.local.entities.ControlOperationEntity
import com.splitfree.data.local.entities.DeliveryEntity
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.data.local.entities.GroupEntity
import com.splitfree.data.local.entities.OutboxEntity
import com.splitfree.data.local.entities.RelaySyncCursorEntity
import com.splitfree.data.local.entities.SyncRevisionEntity

@Database(
    entities = [
        EventEntity::class, GroupEntity::class, OutboxEntity::class, DeliveryEntity::class,
        ControlOperationEntity::class, SyncRevisionEntity::class, RelaySyncCursorEntity::class
    ],
    version = 4,
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
 * - v3: immutable control-operation journal and trigger-maintained per-group sync revisions
 * - v4: per-relay, per-recipient completed catch-up cursors
 */
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao

    abstract fun groupDao(): GroupDao

    abstract fun outboxDao(): OutboxDao

    abstract fun deliveryDao(): DeliveryDao

    abstract fun controlOperationDao(): ControlOperationDao

    abstract fun syncRevisionDao(): SyncRevisionDao

    abstract fun relaySyncCursorDao(): RelaySyncCursorDao

    companion object {
        val SYNC_REVISION_CALLBACK = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                installSyncRevisionTriggers(db)
            }
        }

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS relay_sync_cursors (" +
                        "groupId TEXT NOT NULL, relayUrl TEXT NOT NULL, recipientPubkey TEXT NOT NULL, " +
                        "throughTimestamp INTEGER NOT NULL, PRIMARY KEY(groupId, relayUrl, recipientPubkey))"
                )
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS operation_journal (" +
                        "id TEXT NOT NULL, kind TEXT NOT NULL, intentJson TEXT NOT NULL, " +
                        "preparedJson TEXT, PRIMARY KEY(id))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS sync_revisions (" +
                        "groupId TEXT NOT NULL, revision INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(groupId))"
                )
                installSyncRevisionTriggers(db)
            }
        }

        /**
         * SQLite drops a table's triggers with the table. Any future migration that rebuilds `events`,
         * `deliveries` or `groups` (the usual way to drop or retype a column) must call this again
         * afterwards, or Nearby stops noticing local changes without any error.
         */
        private fun installSyncRevisionTriggers(db: SupportSQLiteDatabase) {
            val columns = mapOf(
                "events" to listOf(
                    "eventId", "groupId", "pubkey", "createdAt", "kind", "contentEncrypted", "eventType",
                    "expenseUuid", "sig", "receivedAt", "originalEventJson", "keyEpoch", "applyState"
                ),
                "deliveries" to listOf(
                    "envelopeId", "groupId", "recipient", "eventId", "envelopeJson", "eventType", "state",
                    "source", "createdAt", "receivedAt", "sizeBytes"
                ),
                // Sync timestamps are bookkeeping, not new reconciliation evidence.
                "groups" to listOf(
                    "groupId", "name", "description", "createdBy", "createdAt", "members", "relays",
                    "memberNames", "lastMetaTimestamp", "keyEpoch", "lastMetaEventId", "memberClocks"
                )
            )

            // A parent's INSERT OR REPLACE overrides trigger conflict policies: avoid a conflict entirely.
            fun bump(row: String): String = "INSERT INTO sync_revisions(groupId, revision) SELECT $row.groupId, 0 " +
                "WHERE NOT EXISTS (SELECT 1 FROM sync_revisions WHERE groupId = $row.groupId); " +
                "UPDATE sync_revisions SET revision = revision + 1 WHERE groupId = $row.groupId; "
            for ((table, fields) in columns) {
                db.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS sync_${table}_insert AFTER INSERT ON `$table` " +
                        "BEGIN ${bump("NEW")}END"
                )
                db.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS sync_${table}_delete AFTER DELETE ON `$table` " +
                        "BEGIN ${bump("OLD")}END"
                )
                val changed = fields.joinToString(" OR ") { "OLD.$it IS NOT NEW.$it" }
                db.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS sync_${table}_update AFTER UPDATE ON `$table` " +
                        "WHEN $changed BEGIN ${bump("OLD")}" +
                        "INSERT INTO sync_revisions(groupId, revision) SELECT NEW.groupId, 0 " +
                        "WHERE NOT EXISTS (SELECT 1 FROM sync_revisions WHERE groupId = NEW.groupId); " +
                        "UPDATE sync_revisions SET revision = revision + 1 " +
                        "WHERE groupId = NEW.groupId AND OLD.groupId IS NOT NEW.groupId; END"
                )
            }
        }

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
