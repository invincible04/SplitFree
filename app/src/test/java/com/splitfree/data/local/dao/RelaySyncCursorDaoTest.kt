package com.splitfree.data.local.dao

import android.app.Application
import androidx.room.Room
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.local.entities.GroupEntity
import com.splitfree.data.local.entities.RelaySyncCursorEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
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
class RelaySyncCursorDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: RelaySyncCursorDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .addCallback(AppDatabase.SYNC_REVISION_CALLBACK)
            .allowMainThreadQueries()
            .build()
        dao = db.relaySyncCursorDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `unknown group or recipient has no trusted coverage`() = runBlocking {
        assertTrue(dao.get("g1", "alice").isEmpty())
        dao.upsert(row())
        assertTrue(dao.get("g2", "alice").isEmpty())
        assertTrue(dao.get("g1", "bob").isEmpty())
    }

    @Test
    fun `primary key isolates group relay and recipient`() = runBlocking {
        val rows = listOf(
            row(),
            row(relayUrl = "wss://nos.lol", through = 200),
            row(groupId = "g2", through = 300),
            row(recipient = "bob", through = 400)
        )
        rows.forEach { dao.upsert(it) }

        assertEquals(rows.take(2).toSet(), dao.get("g1", "alice").toSet())
        assertEquals(listOf(rows[2]), dao.get("g2", "alice"))
        assertEquals(listOf(rows[3]), dao.get("g1", "bob"))
    }

    @Test
    fun `upsert keeps maximum timestamp including values beyond Int range`() = runBlocking {
        val high = Int.MAX_VALUE.toLong() + 100
        for (through in listOf(100L, high, high, 10L, 0L)) {
            dao.upsert(row(through = through))
        }
        assertEquals(listOf(row(through = high)), dao.get("g1", "alice"))
    }

    @Test
    fun `concurrent upserts cannot regress coverage`() = runBlocking {
        coroutineScope {
            (1L..40L).reversed().forEach { through ->
                launch(Dispatchers.IO) { dao.upsert(row(through = through)) }
            }
        }
        assertEquals(listOf(row(through = 40)), dao.get("g1", "alice"))
    }

    @Test
    fun `group replacement preserves coverage and cursor writes do not change sync revision`() = runBlocking {
        val group = GroupEntity(
            groupId = "g1",
            name = "Trip",
            createdBy = "alice",
            createdAt = 1,
            members = "[]",
            relays = "[]"
        )
        db.groupDao().insert(group)
        val before = db.syncRevisionDao().observeRevision("g1").first()
        dao.upsert(row())
        dao.upsert(row(through = 200))
        assertEquals(before, db.syncRevisionDao().observeRevision("g1").first())

        db.groupDao().insert(group.copy(name = "Renamed"))
        assertEquals(listOf(row(through = 200)), dao.get("g1", "alice"))
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_list(relay_sync_cursors)").use {
            assertEquals(0, it.count)
        }
    }

    private fun row(
        groupId: String = "g1",
        relayUrl: String = "wss://relay.damus.io",
        recipient: String = "alice",
        through: Long = 100
    ) = RelaySyncCursorEntity(groupId, relayUrl, recipient, through)
}
