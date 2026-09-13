package com.splitfree.data.local.dao

import android.app.Application
import androidx.room.Room
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.local.entities.GroupEntity
import kotlinx.coroutines.flow.first
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

    // --- overrideMembership (key revocation) ---

    @Test
    fun `overrideMembership always applies the roster and raises the watermark to the given time`() = runBlocking {
        dao.overrideMembership("g1", """["creator","bob2"]""", """{"bob2":"Bob"}""", "", 900, "e-900")

        val row = dao.getById("g1")!!
        assertEquals("""["creator","bob2"]""", row.members)
        assertEquals("""{"bob2":"Bob"}""", row.memberNames)
        assertEquals(900L, row.lastMetaTimestamp)
        assertEquals("e-900", row.lastMetaEventId)
        // Name, relays, description and creator are untouched.
        assertEquals("Trip", row.name)
        assertEquals(entity.relays, row.relays)
        assertEquals(entity.description, row.description)
        assertEquals("creator", row.createdBy)
    }

    @Test
    fun `overrideMembership never moves the watermark backwards`() = runBlocking {
        dao.overrideMembership("g1", """["creator"]""", "{}", "", 100, "e-100")

        val row = dao.getById("g1")!!
        assertEquals("""["creator"]""", row.members)
        assertEquals(500L, row.lastMetaTimestamp)
    }

    @Test
    fun `overrideMembership raises the watermark to the max of stored and given`() = runBlocking {
        dao.updateMetaIfNewer("g1", "Trip", entity.members, entity.relays, "", 800, "{}", null, "e-800")

        // Older than the stored watermark: roster applies, watermark stays at 800.
        dao.overrideMembership("g1", """["creator"]""", "{}", "", 700, "e-700")
        var row = dao.getById("g1")!!
        assertEquals("""["creator"]""", row.members)
        assertEquals(800L, row.lastMetaTimestamp)
        assertEquals("e-800", row.lastMetaEventId)

        // Newer than the stored watermark: watermark advances.
        dao.overrideMembership("g1", """["creator","carol"]""", "{}", "", 950, "e-950")
        row = dao.getById("g1")!!
        assertEquals("""["creator","carol"]""", row.members)
        assertEquals(950L, row.lastMetaTimestamp)
        assertEquals("e-950", row.lastMetaEventId)
    }

    @Test
    fun `overrideMembership only overwrites createdBy when one is supplied`() = runBlocking {
        dao.overrideMembership("g1", """["creator"]""", "{}", "", 900, "e-900")
        assertEquals("creator", dao.getById("g1")!!.createdBy)

        dao.overrideMembership("g1", """["new-creator"]""", "{}", "new-creator", 901, "e-901")
        assertEquals("new-creator", dao.getById("g1")!!.createdBy)
    }

    @Test
    fun `a stale meta replayed after a revocation override is rejected`() = runBlocking {
        // Local revocation override stamped at 900...
        dao.overrideMembership("g1", """["creator"]""", "{}", "", 900, "e-900")
        // ...then a relay replays the pre-revocation meta.
        val updated = dao.updateMetaIfNewer("g1", "Trip", """["creator","bob"]""", entity.relays, "", 800, "{}")

        assertEquals(0, updated)
        assertEquals("""["creator"]""", dao.getById("g1")!!.members)
    }

    // --- applyKeyRotation ---

    @Test
    fun `applyKeyRotation installs epoch and roster together without touching the watermark`() = runBlocking {
        dao.updateMetaIfNewer("g1", "Trip", entity.members, entity.relays, "", 600, "{}", null, "e-600")

        val updated = dao.applyKeyRotation("g1", 1, """["creator"]""", "{}")

        assertEquals(1, updated)
        val row = dao.getById("g1")!!
        assertEquals(1, row.keyEpoch)
        assertEquals("""["creator"]""", row.members)
        assertEquals("{}", row.memberNames)
        assertEquals(600L, row.lastMetaTimestamp)
        assertEquals("e-600", row.lastMetaEventId)
        assertEquals("Trip", row.name)
        assertEquals(entity.relays, row.relays)
    }

    @Test
    fun `applyKeyRotation is a no-op when the group is already at or past the epoch`() = runBlocking {
        dao.insert(entity.copy(groupId = "r", keyEpoch = 2, lastMetaTimestamp = 500))

        // Same epoch: replay.
        assertEquals(0, dao.applyKeyRotation("r", 2, """["mallory"]""", "{}"))
        // Older epoch: replay.
        assertEquals(0, dao.applyKeyRotation("r", 1, """["mallory"]""", "{}"))

        val row = dao.getById("r")!!
        assertEquals(2, row.keyEpoch)
        assertEquals(entity.members, row.members)
        assertEquals(entity.memberNames, row.memberNames)
        assertEquals(500L, row.lastMetaTimestamp)
    }

    @Test
    fun `a pre-rotation meta still applies to name after the rotation because rotations do not raise the watermark`() =
        runBlocking {
            assertEquals(1, dao.applyKeyRotation("g1", 1, """["creator"]""", "{}"))
            assertEquals(500L, dao.getById("g1")!!.lastMetaTimestamp)

            // Creator meta at 600 is newer than the watermark (500) and applies; the repository is
            // responsible for keeping the roster when the meta predates the epoch.
            assertEquals(
                1,
                dao.updateMetaIfNewer("g1", "Renamed", """["creator"]""", entity.relays, "", 600, "{}", null, "e-600")
            )
            val row = dao.getById("g1")!!
            assertEquals("Renamed", row.name)
            assertEquals(1, row.keyEpoch)
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

    // --- ordering ---

    @Test
    fun `getAll and observeAll return newest group first with groupId as tie-breaker`() = runBlocking {
        dao.insert(entity.copy(groupId = "b-newer", createdAt = 3000))
        dao.insert(entity.copy(groupId = "z-tie", createdAt = 2000))
        dao.insert(entity.copy(groupId = "a-tie", createdAt = 2000))
        dao.insert(entity.copy(groupId = "oldest", createdAt = 1))

        val expected = listOf("b-newer", "a-tie", "z-tie", "g1", "oldest")
        assertEquals(expected, dao.getAll().map { it.groupId })
        assertEquals(expected, dao.observeAll().first().map { it.groupId })
    }

    // --- description ---

    @Test
    fun `updateMetaIfNewer sets the description when one is supplied`() = runBlocking {
        dao.insert(entity.copy(groupId = "d", description = "old"))

        dao.updateMetaIfNewer("d", "Trip", entity.members, entity.relays, "", 600, "{}", description = "new")
        assertEquals("new", dao.getById("d")!!.description)

        // A creator can also clear it: empty string is a real value, not "unspecified".
        dao.updateMetaIfNewer("d", "Trip", entity.members, entity.relays, "", 700, "{}", description = "")
        assertEquals("", dao.getById("d")!!.description)
    }

    @Test
    fun `updateMetaIfNewer preserves the description when null is passed`() = runBlocking {
        dao.insert(entity.copy(groupId = "d", description = "keep me"))

        assertEquals(1, dao.updateMetaIfNewer("d", "Renamed", entity.members, entity.relays, "", 600, "{}", null))

        val row = dao.getById("d")!!
        assertEquals("Renamed", row.name)
        assertEquals("keep me", row.description)
    }

    @Test
    fun `overrideMembership never touches the description`() = runBlocking {
        dao.insert(entity.copy(groupId = "d", description = "keep me"))

        dao.overrideMembership("d", """["creator"]""", "{}", "", 900, "e-900")
        assertEquals("keep me", dao.getById("d")!!.description)

        dao.overrideMembership("d", """["creator","x"]""", "{}", "", 901, "e-901")
        assertEquals("keep me", dao.getById("d")!!.description)
    }

    @Test
    fun `a stale meta does not touch the description either`() = runBlocking {
        dao.insert(entity.copy(groupId = "d", description = "current", lastMetaTimestamp = 500))

        assertEquals(0, dao.updateMetaIfNewer("d", "Stale", entity.members, entity.relays, "", 400, "{}", "stale"))

        assertEquals("current", dao.getById("d")!!.description)
    }

    // --- eventId tiebreak ---

    @Test
    fun `updateMetaIfNewer records the eventId alongside the watermark`() = runBlocking {
        assertEquals(
            1,
            dao.updateMetaIfNewer("g1", "Trip", entity.members, entity.relays, "", 600, "{}", null, "e-600")
        )

        val row = dao.getById("g1")!!
        assertEquals(600L, row.lastMetaTimestamp)
        assertEquals("e-600", row.lastMetaEventId)
    }

    @Test
    fun `same-timestamp metas converge on the greater eventId when the lower one arrives first`() = runBlocking {
        assertEquals(
            1,
            dao.updateMetaIfNewer("g1", "Lower", """["creator"]""", entity.relays, "", 600, "{}", null, "aaa")
        )
        assertEquals(
            1,
            dao.updateMetaIfNewer("g1", "Higher", """["creator","carol"]""", entity.relays, "", 600, "{}", null, "bbb")
        )

        val row = dao.getById("g1")!!
        assertEquals("Higher", row.name)
        assertEquals("""["creator","carol"]""", row.members)
        assertEquals(600L, row.lastMetaTimestamp)
        assertEquals("bbb", row.lastMetaEventId)
    }

    @Test
    fun `same-timestamp metas converge on the greater eventId when the higher one arrives first`() = runBlocking {
        assertEquals(
            1,
            dao.updateMetaIfNewer("g1", "Higher", """["creator","carol"]""", entity.relays, "", 600, "{}", null, "bbb")
        )
        assertEquals(
            0,
            dao.updateMetaIfNewer("g1", "Lower", """["creator"]""", entity.relays, "", 600, "{}", null, "aaa")
        )

        val row = dao.getById("g1")!!
        assertEquals("Higher", row.name)
        assertEquals("""["creator","carol"]""", row.members)
        assertEquals(600L, row.lastMetaTimestamp)
        assertEquals("bbb", row.lastMetaEventId)
    }

    @Test
    fun `replaying the exact same meta is a no-op`() = runBlocking {
        assertEquals(1, dao.updateMetaIfNewer("g1", "Trip", entity.members, entity.relays, "", 600, "{}", null, "aaa"))
        assertEquals(0, dao.updateMetaIfNewer("g1", "Trip", entity.members, entity.relays, "", 600, "{}", null, "aaa"))
    }

    @Test
    fun `a newer timestamp wins regardless of eventId order`() = runBlocking {
        assertEquals(1, dao.updateMetaIfNewer("g1", "Trip", entity.members, entity.relays, "", 600, "{}", null, "zzz"))
        assertEquals(1, dao.updateMetaIfNewer("g1", "Later", entity.members, entity.relays, "", 601, "{}", null, "aaa"))

        val row = dao.getById("g1")!!
        assertEquals("Later", row.name)
        assertEquals(601L, row.lastMetaTimestamp)
        assertEquals("aaa", row.lastMetaEventId)
    }

    @Test
    fun `a legacy row with an empty lastMetaEventId accepts a same-timestamp meta once`() = runBlocking {
        // Migrated rows carry lastMetaEventId = '' so the first real meta at the same createdAt applies.
        assertEquals(1, dao.updateMetaIfNewer("g1", "Trip", entity.members, entity.relays, "", 500, "{}", null, "aaa"))
        assertEquals("aaa", dao.getById("g1")!!.lastMetaEventId)
        // ...but an eventId-less call at that timestamp can no longer win.
        assertEquals(0, dao.updateMetaIfNewer("g1", "Trip", entity.members, entity.relays, "", 500, "{}", null, ""))
    }

    @Test
    fun `overrideMembership records the eventId only when its timestamp wins or ties`() = runBlocking {
        dao.overrideMembership("g1", entity.members, "{}", "", 900, "e-900")
        var row = dao.getById("g1")!!
        assertEquals(900L, row.lastMetaTimestamp)
        assertEquals("e-900", row.lastMetaEventId)

        // Older timestamp: roster applies, watermark and eventId stay.
        dao.overrideMembership("g1", """["creator"]""", "{}", "", 100, "e-100")
        row = dao.getById("g1")!!
        assertEquals("""["creator"]""", row.members)
        assertEquals(900L, row.lastMetaTimestamp)
        assertEquals("e-900", row.lastMetaEventId)

        // Equal timestamp: eventId is replaced.
        dao.overrideMembership("g1", entity.members, "{}", "", 900, "e-tie")
        row = dao.getById("g1")!!
        assertEquals(900L, row.lastMetaTimestamp)
        assertEquals("e-tie", row.lastMetaEventId)
    }

    // --- member self-updates ---

    @Test
    fun `updateMemberSelf writes members names and clocks without touching the watermark`() = runBlocking {
        dao.updateMetaIfNewer("g1", "Trip", entity.members, entity.relays, "", 600, "{}", null, "e-600")

        dao.updateMemberSelf(
            "g1",
            """["creator","bob","carol"]""",
            """{"carol":"Carol"}""",
            """{"carol":"700:e-700"}"""
        )

        val row = dao.getById("g1")!!
        assertEquals("""["creator","bob","carol"]""", row.members)
        assertEquals("""{"carol":"Carol"}""", row.memberNames)
        assertEquals("""{"carol":"700:e-700"}""", row.memberClocks)
        assertEquals(600L, row.lastMetaTimestamp)
        assertEquals("e-600", row.lastMetaEventId)
        assertEquals("Trip", row.name)
        assertEquals(entity.relays, row.relays)
        assertEquals("creator", row.createdBy)
    }

    @Test
    fun `a self-update does not block a later creator meta and vice versa`() = runBlocking {
        dao.updateMemberSelf(
            "g1",
            """["creator","bob","carol"]""",
            """{"carol":"Carol"}""",
            """{"carol":"900:e-900"}"""
        )
        assertEquals(500L, dao.getById("g1")!!.lastMetaTimestamp)

        // Creator meta at 600 still applies (watermark is 500), and leaves the clocks alone.
        assertEquals(
            1,
            dao.updateMetaIfNewer("g1", "Trip", """["creator","bob"]""", entity.relays, "", 600, "{}", null, "e-600")
        )
        val row = dao.getById("g1")!!
        assertEquals("""["creator","bob"]""", row.members)
        assertEquals("""{"carol":"900:e-900"}""", row.memberClocks)
    }
}
