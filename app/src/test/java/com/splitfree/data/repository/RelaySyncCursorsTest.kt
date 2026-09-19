package com.splitfree.data.repository

import android.app.Application
import androidx.room.Room
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.local.entities.GroupEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RelaySyncCursorsTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val dbName = "relay-sync-cursors-test.db"
    private lateinit var db: AppDatabase
    private lateinit var repo: RelaySyncCursors

    @Before
    fun setup() {
        context.deleteDatabase(dbName)
        openDatabase()
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun `group-level lastSyncTimestamp does not seed relay coverage`() = runBlocking {
        db.groupDao().insert(
            GroupEntity(
                groupId = "g1",
                name = "Trip",
                createdBy = "alice",
                createdAt = 1,
                members = "[]",
                relays = "[]",
                lastSyncTimestamp = 900
            )
        )
        val cursors = repo.cursors("g1", "alice")
        assertTrue(cursors.isEmpty())
        assertEquals(0L, cursors[RELAY_A] ?: 0L)
        assertEquals(900L, db.groupDao().getById("g1")!!.lastSyncTimestamp)
    }

    @Test
    fun `only completed relays advance and empty completion is a no-op`() = runBlocking {
        repo.advance("g1", "alice", setOf(RELAY_A, RELAY_B), 100)
        repo.advance("g1", "alice", setOf(RELAY_A), 200)
        repo.advance("g1", "alice", emptySet(), 900)
        repo.advance("g2", "alice", emptySet(), 900)

        assertEquals(mapOf(RELAY_A to 200L, RELAY_B to 100L), repo.cursors("g1", "alice"))
        assertTrue(repo.cursors("g2", "alice").isEmpty())
    }

    @Test
    fun `file reopen keeps per-recipient per-group coverage and monotonicity`() = runBlocking {
        repo.advance("g1", "alice", setOf(RELAY_A, RELAY_B), 100)
        repo.advance("g1", "bob", setOf(RELAY_A), 200)
        repo.advance("g2", "alice", setOf(RELAY_A), 300)
        db.close()
        openDatabase()

        assertEquals(mapOf(RELAY_A to 100L, RELAY_B to 100L), repo.cursors("g1", "alice"))
        assertEquals(mapOf(RELAY_A to 200L), repo.cursors("g1", "bob"))
        assertEquals(mapOf(RELAY_A to 300L), repo.cursors("g2", "alice"))
        assertTrue(repo.cursors("g1", "carol").isEmpty())
        repo.advance("g1", "alice", setOf(RELAY_A), 50)
        assertEquals(mapOf(RELAY_A to 100L, RELAY_B to 100L), repo.cursors("g1", "alice"))
    }

    @Test
    fun `overlapping completed batches preserve maximum per relay`() = runBlocking {
        coroutineScope {
            (1L..30L).forEach { through ->
                launch(Dispatchers.IO) {
                    val completed = if (through % 2L == 0L) setOf(RELAY_A, RELAY_B) else setOf(RELAY_A)
                    repo.advance("g1", "alice", completed, through)
                }
            }
        }
        assertEquals(mapOf(RELAY_A to 30L, RELAY_B to 30L), repo.cursors("g1", "alice"))
    }

    @Test
    fun `failure on a later relay rolls back earlier advances and inserted cursors`() = runBlocking {
        repo.advance("g1", "alice", setOf(RELAY_A), 100)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_relay_cursor BEFORE INSERT ON relay_sync_cursors " +
                "WHEN NEW.relayUrl = '$RELAY_B' BEGIN SELECT RAISE(ABORT, 'cursor write rejected'); END"
        )

        val failure = runCatching {
            repo.advance("g1", "alice", linkedSetOf(RELAY_A, RELAY_C, RELAY_B), 200)
        }.exceptionOrNull()
        assertTrue(
            "expected injected cursor failure, got $failure",
            generateSequence(failure) { it.cause }.any { it.message.orEmpty().contains("cursor write rejected") }
        )
        assertEquals(mapOf(RELAY_A to 100L), repo.cursors("g1", "alice"))
        db.close()
        openDatabase()
        assertEquals(mapOf(RELAY_A to 100L), repo.cursors("g1", "alice"))
    }

    @Test
    fun `file reopen retains sweep debt frontier and fair attempt independently per recipient`() = runBlocking {
        val range = com.splitfree.domain.model.sync.HistoryRange(1, 2, "ab")
        val alice = RelaySyncCursors.Sweep(listOf(range), attemptedAt = 100, hadUnresolved = true)
        val bob = RelaySyncCursors.Sweep(emptyList(), attemptedAt = 200, hadUnresolved = false)
        repo.saveSweeps("g1", "alice", mapOf(RELAY_A to alice, RELAY_B to bob))
        repo.saveSweeps("g1", "bob", mapOf(RELAY_A to bob))
        db.close()
        openDatabase()
        assertEquals(mapOf(RELAY_A to alice, RELAY_B to bob), repo.sweeps("g1", "alice"))
        assertEquals(mapOf(RELAY_A to bob), repo.sweeps("g1", "bob"))
        assertTrue(repo.sweeps("g2", "alice").isEmpty())
        repo.saveSweeps("g1", "alice", mapOf(RELAY_A to bob))
        assertEquals(mapOf(RELAY_A to bob, RELAY_B to bob), repo.sweeps("g1", "alice"))
    }

    private fun openDatabase() {
        db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addCallback(AppDatabase.SYNC_REVISION_CALLBACK)
            .allowMainThreadQueries()
            .build()
        repo = RelaySyncCursors(db)
    }

    private companion object {
        const val RELAY_A = "wss://relay.damus.io"
        const val RELAY_B = "wss://nos.lol"
        const val RELAY_C = "wss://relay.primal.net"
    }
}
