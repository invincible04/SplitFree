package com.splitfree.data.local

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.splitfree.data.local.entities.DeliveryEntity
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.di.DatabaseModule
import java.io.File
import kotlinx.coroutines.runBlocking
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

/**
 * Builds a real **version 1** database file from the exact `CREATE TABLE` statements exported in
 * `schemas/com.splitfree.data.local.AppDatabase/1.json`, seeds it, then opens it through Room with
 * [AppDatabase.MIGRATION_1_2] and checks that every pre-existing row survives with the v2 defaults
 * and that the new `deliveries` table is usable.
 *
 * `androidx.room:room-testing` is not available offline, so the v1 fixture is written with the
 * framework `SQLiteDatabase` directly; Room's own schema validation on open then plays the role of
 * `MigrationTestHelper.validateMigration` (a column / index mismatch would throw).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
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

    private fun openV2(name: String = dbName): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
            .also { db = it }

    @Test
    fun `migrated database reports version 2`() {
        val migrated = openV2()

        assertEquals(2, migrated.openHelper.readableDatabase.version)
    }

    @Test
    fun `existing event row survives with applyState APPLIED`() = runBlocking {
        val migrated = openV2()

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
        // Legacy rows are treated as applied, so they still feed projections.
        assertEquals(listOf("e1"), migrated.eventDao().getEventsByGroup("g1").map { it.eventId })
        assertEquals(1, migrated.eventDao().getEventCount("g1"))
    }

    @Test
    fun `existing group row keeps its data and picks up the v2 defaults`() = runBlocking {
        val migrated = openV2()

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
        val migrated = openV2()
        val dao = migrated.groupDao()

        // Same timestamp as the legacy watermark, but any non-empty eventId beats the '' default.
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
        val migrated = openV2()

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
        val migrated = openV2()
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
        // Sanity check on the fixture: without the migration Room must refuse to open v1 as v2
        // rather than silently wiping it.
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

        // ...and with it, nothing was lost.
        assertNotNull(openV2().groupDao().getById("g1"))
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
        assertEquals(2, prod.openHelper.readableDatabase.version)
    }

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

        /** `identityHash` from `1.json`. */
        const val V1_IDENTITY_HASH = "42e63e49719e7b1c9f1cdd1952e96b97"
    }
}
