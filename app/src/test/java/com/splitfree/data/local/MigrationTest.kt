package com.splitfree.data.local

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.splitfree.data.local.entities.DeliveryEntity
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.data.repository.ControlOperationJournal
import com.splitfree.data.repository.RelaySyncCursors
import com.splitfree.di.DatabaseModule
import com.splitfree.domain.repository.ControlOperation
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * Opens seeded v1, v3 and v4 files through Room's v6 schema validation. The v3 fixture uses the
 * exported schema and production revision triggers; the v4 fixture adds relay cursors to it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MigrationTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val dbName = "migration-test.db"
    private var db: AppDatabase? = null

    @Before
    fun setup() {
        deleteDatabase(dbName)
        createV1Database(context.getDatabasePath(dbName))
    }

    @After
    fun tearDown() {
        db?.close()
        deleteDatabase(dbName)
        deleteDatabase(PROD_DB_NAME)
    }

    private fun deleteDatabase(name: String) {
        val file = context.getDatabasePath(name)
        listOf(file, File("${file.path}-journal"), File("${file.path}-wal"), File("${file.path}-shm")).forEach {
            it.delete()
        }
    }

    private fun openCurrent(name: String = dbName): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6
            )
            .addCallback(AppDatabase.SYNC_REVISION_CALLBACK)
            .allowMainThreadQueries()
            .build()
            .also { db = it }

    @Test
    fun `migrated database reports version 6`() {
        val migrated = openCurrent()

        assertEquals(6, migrated.openHelper.readableDatabase.version)
    }

    @Test
    fun `existing event row survives with applyState APPLIED`() = runBlocking {
        val migrated = openCurrent()

        val event = migrated.eventDao().getEvent("e1")
        assertNotNull(event)
        assertEquals(EventEntity.APPLY_STATE_APPLIED, event!!.applyState)
        assertEquals("g1", event.groupId)
        assertEquals("author", event.pubkey)
        assertEquals(100L, event.createdAt)
        assertEquals(30078, event.kind)
        assertEquals("cipher", event.contentEncrypted)
        assertEquals("expense", event.eventType)
        assertEquals("uuid-1", event.expenseUuid)
        assertEquals("sig-1", event.sig)
        assertEquals(101L, event.receivedAt)
        assertEquals("""{"id":"e1"}""", event.originalEventJson)
        assertEquals(0, event.keyEpoch)
        // Rows migrated from v1 are treated as applied, so they still feed projections.
        assertEquals(listOf("e1"), migrated.eventDao().getEventsByGroup("g1").map { it.eventId })
        assertEquals(1, migrated.eventDao().getEventCount("g1"))
    }

    @Test
    fun `existing group row keeps its data and picks up the v2 defaults`() = runBlocking {
        val migrated = openCurrent()

        val group = migrated.groupDao().getById("g1")
        assertNotNull(group)
        assertEquals("Trip", group!!.name)
        assertEquals("A trip", group.description)
        assertEquals("creator", group.createdBy)
        assertEquals(1000L, group.createdAt)
        assertEquals("""["creator","bob"]""", group.members)
        assertEquals("""["wss://r"]""", group.relays)
        assertEquals("""{"bob":"Bob"}""", group.memberNames)
        assertEquals(42L, group.lastSyncTimestamp)
        assertEquals(5L, group.lastMetaTimestamp)
        assertEquals(3, group.keyEpoch)
        assertEquals("", group.lastMetaEventId)
        assertEquals("{}", group.memberClocks)
    }

    @Test
    fun `migrated group still accepts the new tiebreak and self-update writes`() = runBlocking {
        val migrated = openCurrent()
        val dao = migrated.groupDao()

        // Same timestamp as the migrated v1 watermark, but any non-empty eventId beats the '' default.
        assertEquals(
            1,
            dao.updateMetaIfNewer("g1", "Trip", """["creator"]""", """["wss://r"]""", "", 5, "{}", null, "aaa")
        )
        assertEquals("aaa", dao.getById("g1")!!.lastMetaEventId)

        dao.updateMemberSelf("g1", """["creator","bob"]""", """{"bob":"Bobby"}""", """{"bob":"7:e9"}""")
        val row = dao.getById("g1")!!
        assertEquals("""{"bob":"7:e9"}""", row.memberClocks)
        assertEquals(5L, row.lastMetaTimestamp)
    }

    @Test
    fun `existing outbox row survives`() = runBlocking {
        val migrated = openCurrent()

        val outbox = migrated.outboxDao().getAll()
        assertEquals(1, outbox.size)
        assertEquals("o1", outbox[0].eventId)
        assertEquals("""{"id":"o1"}""", outbox[0].eventJson)
        assertEquals(200L, outbox[0].createdAt)
        assertEquals(2, outbox[0].retryCount)
        assertEquals(201L, outbox[0].lastRetryAt)
        assertEquals("expense", outbox[0].eventType)
    }

    @Test
    fun `deliveries table is created and usable`() = runBlocking {
        val migrated = openCurrent()
        val dao = migrated.deliveryDao()
        val delivery =
            DeliveryEntity(
                envelopeId = "env-1",
                groupId = "g1",
                recipient = "bob",
                eventId = "inner-1",
                envelopeJson = """{"id":"env-1"}""",
                eventType = DeliveryEntity.TYPE_GIFT_WRAP,
                state = DeliveryEntity.STATE_AVAILABLE,
                source = DeliveryEntity.SOURCE_AUTHORED,
                createdAt = 300,
                receivedAt = 301,
                sizeBytes = 14
            )

        assertTrue(dao.insertIfNew(delivery))
        assertEquals(delivery, dao.get("env-1"))
        assertEquals(listOf("env-1"), dao.getAvailable("g1").map { it.envelopeId })
        assertEquals(1, dao.countAvailable("g1"))
    }

    @Test
    fun `migration does not run the destructive fallback`() = runBlocking {
        // Without a registered migration, opening v1 must fail rather than wipe its data.
        val failure = runCatching {
            Room.databaseBuilder(context, AppDatabase::class.java, dbName)
                .allowMainThreadQueries()
                .build()
                .also { db = it }
                .groupDao()
                .getById("g1")
        }.exceptionOrNull()

        val chain = generateSequence(failure) { it.cause }.toList()
        assertTrue(
            "expected a missing-migration failure, got $failure",
            chain.any { it is IllegalStateException && it.message.orEmpty().contains("migration", ignoreCase = true) }
        )
        db?.close()
        db = null

        // Registering the migration recovers the seeded group.
        assertNotNull(openCurrent().groupDao().getById("g1"))
    }

    @Test
    fun `DatabaseModule registers the migration for the production database`() = runBlocking {
        deleteDatabase(PROD_DB_NAME)
        createV1Database(context.getDatabasePath(PROD_DB_NAME))

        val prod = DatabaseModule.provideDatabase(context).also { db = it }

        val group = prod.groupDao().getById("g1")
        assertNotNull(group)
        assertEquals(5L, group!!.lastMetaTimestamp)
        assertEquals("", group.lastMetaEventId)
        assertEquals("{}", group.memberClocks)
        assertEquals(EventEntity.APPLY_STATE_APPLIED, prod.eventDao().getEvent("e1")!!.applyState)
        assertEquals(1, prod.outboxDao().count())
        assertNull(prod.deliveryDao().get("nope"))
        assertEquals(6, prod.openHelper.readableDatabase.version)
    }

    @Test
    fun `v2 to v3 preserves pending evidence and outbox and exposes immutable operation journal`() = runBlocking {
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(dbName).path,
            null,
            SQLiteDatabase.OPEN_READWRITE
        ).use { raw ->
            raw.execSQL("ALTER TABLE events ADD COLUMN applyState INTEGER NOT NULL DEFAULT 0")
            raw.execSQL("ALTER TABLE groups ADD COLUMN lastMetaEventId TEXT NOT NULL DEFAULT ''")
            raw.execSQL("ALTER TABLE groups ADD COLUMN memberClocks TEXT NOT NULL DEFAULT '{}'")
            val schemaFile = listOf(
                File("schemas/com.splitfree.data.local.AppDatabase/2.json"),
                File("app/schemas/com.splitfree.data.local.AppDatabase/2.json")
            ).first { it.exists() }
            val entities = JSONObject(schemaFile.readText()).getJSONObject("database").getJSONArray("entities")
            val deliveries = (0 until entities.length()).map { entities.getJSONObject(it) }
                .first { it.getString("tableName") == "deliveries" }
            raw.execSQL(deliveries.getString("createSql").replace("\${TABLE_NAME}", "deliveries"))
            val indices = deliveries.getJSONArray("indices")
            for (i in 0 until indices.length()) {
                raw.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", "deliveries"))
            }
            raw.execSQL("UPDATE events SET applyState = 1")
            raw.version = 2
        }
        val migrated = openCurrent()
        assertEquals(EventEntity.APPLY_STATE_PENDING, migrated.eventDao().getEvent("e1")!!.applyState)
        assertEquals(1, migrated.outboxDao().count())
        assertNotNull(migrated.groupDao().getById("g1"))
        val journal = ControlOperationJournal(migrated.controlOperationDao())
        val operation = ControlOperation("rotation:g1", "rotation", "public intent")
        journal.insert(operation)
        journal.prepare(operation.id, "ciphertext envelopes")
        assertTrue(runCatching { journal.prepare(operation.id, "different envelopes") }.isFailure)
        assertTrue(runCatching { journal.insert(operation.copy(intentJson = "changed target")) }.isFailure)
        assertEquals("ciphertext envelopes", journal.get(operation.id)!!.preparedJson)
        val before = migrated.syncRevisionDao().observeRevision("g1").first()
        migrated.openHelper.writableDatabase.execSQL("UPDATE events SET applyState = 0 WHERE eventId = 'e1'")
        assertEquals(before + 1, migrated.syncRevisionDao().observeRevision("g1").first())
        migrated.close()
        db = null
        val reopened = openCurrent()
        assertEquals(
            "ciphertext envelopes",
            ControlOperationJournal(reopened.controlOperationDao()).get(operation.id)!!.preparedJson
        )
    }

    @Test
    fun `v3 to v5 preserves every v3 table and revision trigger without trusting global sync`() = runBlocking {
        val fixture = createV3Database(dbName)
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6)
            .allowMainThreadQueries()
            .build()
            .also { db = it }
        val sql = migrated.openHelper.writableDatabase

        assertEquals(6, sql.version)
        assertEquals(
            fixture.rows,
            tableRows(sql, fixture.rows.keys).mapValues { (table, rows) ->
                if (table == "groups") rows.map { it.dropLast(1) } else rows
            }
        )
        assertEquals(9, fixture.triggers.size)
        assertEquals(fixture.triggers, triggerDefinitions(sql))
        val cursors = RelaySyncCursors(migrated)
        assertEquals(900L, migrated.groupDao().getById("g1")!!.lastSyncTimestamp)
        assertTrue(cursors.cursors("g1", "alice").isEmpty())
        assertEquals(0L, cursors.cursors("g1", "alice")[RELAY_URL] ?: 0L)

        val before = migrated.syncRevisionDao().observeRevision("g1").first()
        sql.execSQL("UPDATE events SET applyState = 0 WHERE eventId = 'e3'")
        sql.execSQL("UPDATE deliveries SET state = 1 WHERE envelopeId = 'env3'")
        sql.execSQL("UPDATE groups SET name = 'Renamed' WHERE groupId = 'g1'")
        assertEquals(before + 3, migrated.syncRevisionDao().observeRevision("g1").first())
        cursors.advance("g1", "alice", setOf(RELAY_URL), 500)
        assertEquals(before + 3, migrated.syncRevisionDao().observeRevision("g1").first())

        migrated.close()
        db = null
        val reopened = openCurrent()
        val reopenedCursors = RelaySyncCursors(reopened)
        assertEquals(mapOf(RELAY_URL to 500L), reopenedCursors.cursors("g1", "alice"))
        assertTrue(reopenedCursors.cursors("g1", "bob").isEmpty())
        assertEquals(fixture.triggers, triggerDefinitions(reopened.openHelper.readableDatabase))
        assertEquals(before + 3, reopened.syncRevisionDao().observeRevision("g1").first())
        reopenedCursors.advance("g1", "alice", setOf(RELAY_URL), 100)
        assertEquals(mapOf(RELAY_URL to 500L), reopenedCursors.cursors("g1", "alice"))
    }

    @Test
    fun `DatabaseModule registers v3 to v6 migration without data loss`() = runBlocking {
        val fixture = createV3Database(PROD_DB_NAME)
        val migrated = DatabaseModule.provideDatabase(context).also { db = it }
        val sql = migrated.openHelper.readableDatabase

        assertEquals(6, sql.version)
        assertEquals(
            fixture.rows,
            tableRows(sql, fixture.rows.keys).mapValues { (table, rows) ->
                if (table == "groups") rows.map { it.dropLast(1) } else rows
            }
        )
        assertEquals(fixture.triggers, triggerDefinitions(sql))
        assertTrue(migrated.relaySyncCursorDao().get("g1", "alice").isEmpty())
    }

    @Test
    fun `v4 upgrade preserves all data and adds empty recovery publications`() = runBlocking {
        val fixture = createV3Database(dbName)
        val raw = SQLiteDatabase.openDatabase(context.getDatabasePath(dbName).path, null, SQLiteDatabase.OPEN_READWRITE)
        raw.execSQL(
            "CREATE TABLE relay_sync_cursors (groupId TEXT NOT NULL, relayUrl TEXT NOT NULL, " +
                "recipientPubkey TEXT NOT NULL, throughTimestamp INTEGER NOT NULL, PRIMARY KEY(groupId,relayUrl,recipientPubkey))"
        )
        raw.execSQL("INSERT INTO relay_sync_cursors VALUES ('g1','wss://relay.test','alice',123)")
        raw.version = 4
        raw.close()
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6).allowMainThreadQueries().build().also {
                db =
                    it
            }
        val sql = migrated.openHelper.readableDatabase
        assertEquals(6, sql.version)
        assertEquals(
            fixture.rows,
            tableRows(sql, fixture.rows.keys).mapValues { (table, rows) ->
                if (table == "groups") rows.map { it.dropLast(1) } else rows
            }
        )
        assertEquals("", migrated.groupDao().getById("g1")!!.projectionJson)
        assertNull(migrated.displayNameDao().getIntent("alice"))
        assertEquals(fixture.triggers, triggerDefinitions(sql))
    }

    @Test
    fun `v5 migration adds durable sweep frontier without changing existing rows or triggers`() = runBlocking {
        deleteDatabase(dbName)
        val schema = JSONObject(
            listOf(
                File("schemas/com.splitfree.data.local.AppDatabase/5.json"),
                File("app/schemas/com.splitfree.data.local.AppDatabase/5.json")
            ).first { it.exists() }.readText()
        ).getJSONObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        val entities = schema.getJSONArray("entities")
                        for (i in 0 until entities.length()) {
                            val entity = entities.getJSONObject(i)
                            val table = entity.getString("tableName")
                            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                            val indices = entity.optJSONArray("indices") ?: continue
                            for (j in 0 until indices.length()) {
                                db.execSQL(
                                    indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table)
                                )
                            }
                        }
                        AppDatabase.SYNC_REVISION_CALLBACK.onCreate(db)
                        db.execSQL("INSERT INTO outbox VALUES ('old','{}',1,0,NULL,'expense')")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                        error("fixture")
                }).build()
        )
        val triggers = helper.use { triggerDefinitions(it.writableDatabase) }
        val migrated = openCurrent()
        assertEquals(triggers, triggerDefinitions(migrated.openHelper.readableDatabase))
        assertEquals("old", migrated.outboxDao().getAll().single().eventId)
        val cursors = RelaySyncCursors(migrated)
        val range = com.splitfree.domain.model.sync.HistoryRange(123, 123, "ab")
        cursors.saveSweeps("g1", "alice", mapOf(RELAY_URL to RelaySyncCursors.Sweep(listOf(range), 1234L, true)))
        migrated.close()
        db = null
        val reopened = RelaySyncCursors(openCurrent())
        assertEquals(
            RelaySyncCursors.Sweep(listOf(range), 1234L, true),
            reopened.sweeps("g1", "alice").getValue(RELAY_URL)
        )
        assertTrue(reopened.sweeps("g1", "bob").isEmpty())
        assertTrue(reopened.sweeps("g2", "alice").isEmpty())
    }

    private fun createV3Database(name: String): V3Fixture {
        deleteDatabase(name)
        val schemaFile = listOf(
            File("schemas/com.splitfree.data.local.AppDatabase/3.json"),
            File("app/schemas/com.splitfree.data.local.AppDatabase/3.json")
        ).first { it.exists() }
        val schema = JSONObject(schemaFile.readText()).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        val tables = (0 until entities.length()).map { entities.getJSONObject(it).getString("tableName") }
        val callback = object : SupportSQLiteOpenHelper.Callback(3) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices") ?: continue
                    for (j in 0 until indices.length()) {
                        db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                AppDatabase.SYNC_REVISION_CALLBACK.onCreate(db)
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                error("The fixture must be created directly at version 3")
            }
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build()
        )
        return helper.use {
            val sql = it.writableDatabase
            sql.execSQL(
                "INSERT INTO events VALUES " +
                    "('e3', 'g1', 'author', 100, 30078, 'cipher', 'expense', 'uuid-3', " +
                    "'sig-3', 101, '{\"id\":\"e3\"}', 2, 1)"
            )
            sql.execSQL(
                "INSERT INTO groups VALUES ('g1', 'Trip', 'A trip', 'creator', 1000, " +
                    "'[\"creator\",\"alice\"]', '[\"$RELAY_URL\"]', '{\"alice\":\"Alice\"}', " +
                    "900, 500, 2, 'meta-3', '{\"alice\":\"400:self-3\"}')"
            )
            sql.execSQL("INSERT INTO outbox VALUES ('o3', '{\"id\":\"o3\"}', 200, 2, 201, 'expense')")
            sql.execSQL(
                "INSERT INTO deliveries VALUES ('env3', 'g1', 'alice', 'e3', '{\"id\":\"env3\"}', " +
                    "'gift_wrap', 0, 1, 100, 101, 13)"
            )
            sql.execSQL("INSERT INTO operation_journal VALUES ('rotation:g1', 'rotation', 'intent', 'prepared')")
            sql.execSQL("UPDATE sync_revisions SET revision = 42 WHERE groupId = 'g1'")
            assertEquals(3, sql.version)
            V3Fixture(tableRows(sql, tables), triggerDefinitions(sql))
        }
    }

    private fun tableRows(db: SupportSQLiteDatabase, tables: Collection<String>): Map<String, List<List<String?>>> =
        tables.associateWith { table ->
            db.query("SELECT * FROM `$table` ORDER BY rowid").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) })
                    }
                }
            }
        }

    private fun triggerDefinitions(db: SupportSQLiteDatabase): Map<String, String> =
        db.query("SELECT name, sql FROM sqlite_master WHERE type = 'trigger' ORDER BY name").use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1))
            }
        }

    private data class V3Fixture(val rows: Map<String, List<List<String?>>>, val triggers: Map<String, String>)

    // --- v1 fixture ---

    /**
     * Writes a schema-version-1 file using the `createSql` / `setupQueries` from `1.json`
     * (with `${TABLE_NAME}` substituted), then seeds one row per table.
     */
    private fun createV1Database(file: File) {
        file.parentFile?.mkdirs()
        val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            raw.execSQL(
                "CREATE TABLE IF NOT EXISTS `events` (`eventId` TEXT NOT NULL, `groupId` TEXT NOT NULL, " +
                    "`pubkey` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `kind` INTEGER NOT NULL, " +
                    "`contentEncrypted` TEXT NOT NULL, `eventType` TEXT NOT NULL, `expenseUuid` TEXT, " +
                    "`sig` TEXT NOT NULL, `receivedAt` INTEGER NOT NULL, `originalEventJson` TEXT, " +
                    "`keyEpoch` INTEGER NOT NULL, PRIMARY KEY(`eventId`))"
            )
            raw.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_events_groupId_createdAt` ON `events` (`groupId`, `createdAt`)"
            )
            raw.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_events_groupId_eventType` ON `events` (`groupId`, `eventType`)"
            )
            raw.execSQL("CREATE INDEX IF NOT EXISTS `index_events_expenseUuid` ON `events` (`expenseUuid`)")
            raw.execSQL(
                "CREATE TABLE IF NOT EXISTS `groups` (`groupId` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                    "`description` TEXT NOT NULL, `createdBy` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                    "`members` TEXT NOT NULL, `relays` TEXT NOT NULL, `memberNames` TEXT NOT NULL, " +
                    "`lastSyncTimestamp` INTEGER NOT NULL, `lastMetaTimestamp` INTEGER NOT NULL, " +
                    "`keyEpoch` INTEGER NOT NULL, PRIMARY KEY(`groupId`))"
            )
            raw.execSQL(
                "CREATE TABLE IF NOT EXISTS `outbox` (`eventId` TEXT NOT NULL, `eventJson` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, `retryCount` INTEGER NOT NULL, `lastRetryAt` INTEGER, " +
                    "`eventType` TEXT, PRIMARY KEY(`eventId`))"
            )
            raw.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            raw.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '$V1_IDENTITY_HASH')")

            raw.execSQL(
                "INSERT INTO events (eventId, groupId, pubkey, createdAt, kind, contentEncrypted, eventType, " +
                    "expenseUuid, sig, receivedAt, originalEventJson, keyEpoch) VALUES " +
                    "('e1', 'g1', 'author', 100, 30078, 'cipher', 'expense', 'uuid-1', 'sig-1', 101, '{\"id\":\"e1\"}', 0)"
            )
            raw.execSQL(
                "INSERT INTO `groups` (groupId, name, description, createdBy, createdAt, members, relays, " +
                    "memberNames, lastSyncTimestamp, lastMetaTimestamp, keyEpoch) VALUES " +
                    "('g1', 'Trip', 'A trip', 'creator', 1000, '[\"creator\",\"bob\"]', '[\"wss://r\"]', " +
                    "'{\"bob\":\"Bob\"}', 42, 5, 3)"
            )
            raw.execSQL(
                "INSERT INTO outbox (eventId, eventJson, createdAt, retryCount, lastRetryAt, eventType) VALUES " +
                    "('o1', '{\"id\":\"o1\"}', 200, 2, 201, 'expense')"
            )
            raw.version = 1
        } finally {
            raw.close()
        }
    }

    private companion object {
        /** Production database name used by [DatabaseModule.provideDatabase]. */
        const val PROD_DB_NAME = "splitfree.db"
        const val RELAY_URL = "wss://relay.damus.io"

        /** `identityHash` from `1.json`. */
        const val V1_IDENTITY_HASH = "42e63e49719e7b1c9f1cdd1952e96b97"
    }
}
