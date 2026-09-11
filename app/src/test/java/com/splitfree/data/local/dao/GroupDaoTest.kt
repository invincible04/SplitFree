package com.splitfree.data.local.dao

import android.app.Application
import androidx.room.Room
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.local.entities.GroupEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Exercises the real SQL behind the metadata last-writer-wins updates against an
 * in-memory Room database, so the single-statement guarantees are verified rather
 * than assumed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class GroupDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: GroupDao

    private val entity =
        GroupEntity(
            groupId = "g1",
            name = "Trip",
            createdBy = "creator",
            createdAt = 1000,
            members = """["creator","bob"]""",
            relays = """["wss://r"]""",
            memberNames = """{"bob":"Bob"}""",
            lastMetaTimestamp = 500
        )

    @Before
    fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.groupDao()
        dao.insert(entity)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `updateMetaIfNewer applies metadata and watermark together when newer`() = runBlocking {
        val updated = dao.updateMetaIfNewer("g1", "Trip 2", """["creator"]""", """["wss://x"]""", "", 600, "{}")

        assertEquals(1, updated)
        val row = dao.getById("g1")!!
        assertEquals("Trip 2", row.name)
        assertEquals("""["creator"]""", row.members)
        assertEquals("""["wss://x"]""", row.relays)
        assertEquals("{}", row.memberNames)
        assertEquals(600L, row.lastMetaTimestamp)
        assertEquals("creator", row.createdBy)
    }

    @Test
    fun `updateMetaIfNewer leaves the row untouched when not newer`() = runBlocking {
        val updated = dao.updateMetaIfNewer("g1", "Stale", """["mallory"]""", """["wss://x"]""", "mallory", 500, "{}")

        assertEquals(0, updated)
        assertEquals(entity, dao.getById("g1"))
    }

    @Test
    fun `updateMetaIfNewer only overwrites createdBy when one is supplied`() = runBlocking {
        dao.updateMetaIfNewer("g1", "Trip", entity.members, entity.relays, "", 600, entity.memberNames)
        assertEquals("creator", dao.getById("g1")!!.createdBy)

        dao.updateMetaIfNewer("g1", "Trip", entity.members, entity.relays, "new-creator", 700, entity.memberNames)
        assertEquals("new-creator", dao.getById("g1")!!.createdBy)
    }

    @Test
    fun `updateMeta always applies and advances the watermark to the given time`() = runBlocking {
        dao.updateMeta("g1", "Local", """["creator"]""", entity.relays, "", 900, "{}")

        val row = dao.getById("g1")!!
        assertEquals("Local", row.name)
        assertEquals("""["creator"]""", row.members)
        assertEquals(900L, row.lastMetaTimestamp)
    }

    @Test
    fun `updateMeta never moves the watermark backwards`() = runBlocking {
        dao.updateMeta("g1", "Local", """["creator"]""", entity.relays, "", 100, "{}")

        val row = dao.getById("g1")!!
        assertEquals("Local", row.name)
        assertEquals(500L, row.lastMetaTimestamp)
    }

    @Test
    fun `a stale meta replayed after a local mutation is rejected`() = runBlocking {
        // Local removal of bob stamped at 900...
        dao.updateMeta("g1", "Trip", """["creator"]""", entity.relays, "", 900, "{}")
        // ...then a relay replays the pre-removal meta.
        val updated = dao.updateMetaIfNewer("g1", "Trip", """["creator","bob"]""", entity.relays, "", 800, "{}")

        assertEquals(0, updated)
        assertEquals("""["creator"]""", dao.getById("g1")!!.members)
    }

    @Test
    fun `updateCreator sets createdBy and createdAt without moving the watermark`() = runBlocking {
        dao.insert(entity.copy(groupId = "legacy", createdBy = "", createdAt = 999_999, lastMetaTimestamp = 500))

        dao.updateCreator("legacy", "verified-creator", 1234)

        val row = dao.getById("legacy")!!
        assertEquals("verified-creator", row.createdBy)
        assertEquals(1234L, row.createdAt)
        assertEquals(500L, row.lastMetaTimestamp)
        assertEquals(entity.members, row.members)
        // Historical metas newer than the watermark still apply after the creator is recorded.
        assertEquals(1, dao.updateMetaIfNewer("legacy", "Trip", entity.members, entity.relays, "", 600, "{}"))
        assertEquals("creator", dao.getById("g1")!!.createdBy) // other rows untouched
    }
}
