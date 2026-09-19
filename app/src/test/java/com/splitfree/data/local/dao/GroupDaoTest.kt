package com.splitfree.data.local.dao

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.local.entities.GroupEntity
import com.splitfree.data.repository.GroupRepository
import com.splitfree.test.FakeSecureStorage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Exercises metadata ordering and roster projections with in-memory Room. Scripted interleavings
 * check stale-read protection; these tests do not explore arbitrary concurrent schedules.
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

    // --- resetRosterProjection (out-of-order history on import) ---

    @Test
    fun `resetRosterProjection clears the projection but keeps clocks epoch name description and relays`() =
        runBlocking {
            val clocks = """{"revoked:bob":"400:r","replaced:bob":"bob2","succeeded:bob":"bob2","seated:bob":"bob2"}"""
            dao.insert(
                entity.copy(
                    groupId = "p",
                    description = "Ski",
                    memberClocks = clocks,
                    keyEpoch = 3,
                    lastMetaEventId = "e-500",
                    lastSyncTimestamp = 77
                )
            )

            // A record at 400 is older than the watermark (500, "e-500"): the projection is reset.
            assertEquals(1, dao.resetRosterProjection("p", 400, "r"))

            val row = dao.getById("p")!!
            assertEquals("[]", row.members)
            assertEquals("{}", row.memberNames)
            assertEquals("", row.createdBy)
            assertEquals(0L, row.lastMetaTimestamp)
            assertEquals("", row.lastMetaEventId)
            assertEquals(clocks, row.memberClocks)
            assertEquals(3, row.keyEpoch)
            assertEquals("Trip", row.name)
            assertEquals("Ski", row.description)
            assertEquals(entity.relays, row.relays)
            assertEquals(1000L, row.createdAt)
            assertEquals(77L, row.lastSyncTimestamp)
            assertEquals(entity, dao.getById("g1")) // other rows untouched
        }

    @Test
    fun `resetRosterProjection is a no-op unless the watermark is canonically newer than the record`() = runBlocking {
        dao.insert(entity.copy(groupId = "p", lastMetaEventId = "e-500"))
        val before = dao.getById("p")!!

        // Newer than, equal to, and same instant with a greater id than the watermark: nothing to re-project.
        assertEquals(0, dao.resetRosterProjection("p", 600, "a"))
        assertEquals(0, dao.resetRosterProjection("p", 500, "e-500"))
        assertEquals(0, dao.resetRosterProjection("p", 500, "e-501"))
        assertEquals(before, dao.getById("p"))

        // Same instant, lower id: canonically older, so it is reset.
        assertEquals(1, dao.resetRosterProjection("p", 500, "e-499"))
        assertEquals("[]", dao.getById("p")!!.members)
        // Once reset the watermark is zero and nothing is older than it.
        assertEquals(0, dao.resetRosterProjection("p", 1, ""))
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
    fun `a migrated row with an empty lastMetaEventId accepts a same-timestamp meta once`() = runBlocking {
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

    private fun repository(groupDao: GroupDao = dao) = GroupRepository(groupDao, FakeSecureStorage())

    private fun interleaveAfterRead(change: suspend () -> Unit): GroupDao = object : GroupDao by dao {
        private var changed = false

        override suspend fun getById(groupId: String): GroupEntity? {
            val snapshot = dao.getById(groupId)
            if (!changed) {
                changed = true
                change()
            }
            return snapshot
        }
    }

    @Test
    fun `creator and self names converge for both orders including missing empty and tied names`() = runBlocking {
        val clocks = listOf(100L to "self", 300L to "self", 200L to "aaa", 200L to "zzz", 200L to "meta")
        for ((selfTimestamp, selfId) in clocks) {
            for (creatorName in listOf(null, "", "   ", " Creator ")) {
                for (selfName in listOf(null, "", "   ", " Self ")) {
                    var firstOrder: GroupEntity? = null
                    for (selfFirst in listOf(true, false)) {
                        dao.insert(entity.copy(lastMetaTimestamp = 0))
                        val repo = repository()
                        val applySelf: suspend () -> Unit = {
                            repo.applyMemberSelfUpdate("g1", "bob", selfTimestamp, selfId, false, selfName, 0)
                        }
                        val applyMeta: suspend () -> Unit = {
                            repo.updateFromMeta(
                                "g1",
                                "Renamed",
                                listOf("creator", "bob"),
                                listOf("wss://new"),
                                200,
                                memberNames = creatorName?.let { mapOf("bob" to it) } ?: emptyMap(),
                                eventId = "meta",
                                expectedKeyEpoch = 0
                            )
                        }
                        if (selfFirst) {
                            applySelf()
                            applyMeta()
                        } else {
                            applyMeta()
                            applySelf()
                        }
                        val selfWins = selfName != null &&
                            (selfTimestamp > 200 || (selfTimestamp == 200L && selfId > "meta"))
                        val expectedName = (if (selfWins) selfName else creatorName)?.trim()?.takeIf { it.isNotEmpty() }
                        val expectedNames = expectedName?.let { mapOf("bob" to it) } ?: emptyMap()
                        val label = "$selfTimestamp:$selfId creator=$creatorName self=$selfName selfFirst=$selfFirst"
                        assertEquals(label, expectedNames, repo.getById("g1")!!.memberNames)
                        val row = dao.getById("g1")!!
                        assertEquals(200L, row.lastMetaTimestamp)
                        if (firstOrder == null) firstOrder = row else assertEquals(label, firstOrder, row)
                        applySelf()
                        applyMeta()
                        assertEquals("replays: $label", row, dao.getById("g1"))
                    }
                }
            }
        }
    }

    @Test
    fun `null name join does not suppress explicit names across all arrival permutations`() = runBlocking {
        val orders = listOf(
            listOf(0, 1, 2),
            listOf(0, 2, 1),
            listOf(1, 0, 2),
            listOf(1, 2, 0),
            listOf(2, 0, 1),
            listOf(2, 1, 0)
        )
        for (creatorTimestamp in listOf(150L, 250L)) {
            var expected: GroupEntity? = null
            for (order in orders) {
                dao.insert(entity.copy(lastMetaTimestamp = 0))
                val repo = repository()
                val steps: List<suspend () -> Unit> = listOf(
                    { repo.applyMemberSelfUpdate("g1", "bob", 300, "join", true, null, 0) },
                    { repo.applyMemberSelfUpdate("g1", "bob", 200, "self", false, "Self", 0) },
                    {
                        repo.updateFromMeta(
                            "g1",
                            "Trip",
                            listOf("creator", "bob"),
                            emptyList(),
                            creatorTimestamp,
                            memberNames = mapOf("bob" to "Creator"),
                            eventId = "meta",
                            expectedKeyEpoch = 0
                        )
                    }
                )
                order.forEach { steps[it]() }
                assertEquals(if (creatorTimestamp < 200) "Self" else "Creator", repo.getById("g1")!!.memberNames["bob"])
                val row = dao.getById("g1")!!
                val parsedClocks = Json.decodeFromString<Map<String, String>>(row.memberClocks)
                assertEquals(mapOf("bob" to "200:self", "join:bob" to "300:join"), parsedClocks)
                val canonical = row.copy(memberClocks = "")
                if (expected == null) expected = canonical else assertEquals("order=$order", expected, canonical)
                assertFalse(repo.applyMemberSelfUpdate("g1", "bob", 300, "join", true, null, 0))
                assertEquals(row, dao.getById("g1"))
            }
        }
    }

    @Test
    fun `current epoch self join is independent of newer creator and self name clocks`() = runBlocking {
        dao.insert(
            entity.copy(
                members = """["creator"]""",
                memberNames = "{}",
                keyEpoch = 2,
                lastMetaTimestamp = 500,
                memberClocks = """{"bob":"900:self"}"""
            )
        )
        val repo = repository()
        assertTrue(repo.applyMemberSelfUpdate("g1", "bob", 100, "join", true, "Old", 2))
        assertEquals(listOf("creator", "bob"), repo.getById("g1")!!.members)
        assertEquals(emptyMap<String, String>(), repo.getById("g1")!!.memberNames)
        assertFalse(repo.applyMemberSelfUpdate("g1", "bob", 100, "join", true, "Old", 2))
    }

    @Test
    fun `old epoch cannot self join but current members can apply historical names`() = runBlocking {
        val repo = repository()
        assertTrue(repo.applyKeyRotation("g1", 1, listOf("creator"), emptyMap()))
        assertFalse(repo.applyMemberSelfUpdate("g1", "bob", 600, "removed", true, "Bob", 0))
        assertTrue(repo.applyMemberSelfUpdate("g1", "creator", 600, "historical", false, "Creator", 0))
        assertEquals(listOf("creator"), repo.getById("g1")!!.members)
        assertEquals(mapOf("creator" to "Creator"), repo.getById("g1")!!.memberNames)
    }

    @Test
    fun `rotation between self read and write cannot resurrect removed author`() = runBlocking {
        val repo = repository(
            interleaveAfterRead {
                assertEquals(1, dao.applyKeyRotation("g1", 1, """["creator"]""", "{}"))
            }
        )
        assertFalse(repo.applyMemberSelfUpdate("g1", "bob", 600, "self", true, "Bob", 0))
        val row = dao.getById("g1")!!
        assertEquals(1, row.keyEpoch)
        assertEquals("""["creator"]""", row.members)
        assertEquals("{}", row.memberNames)
        assertEquals("{}", row.memberClocks)
    }

    @Test
    fun `rotation between meta read and write preserves roster while applying historical metadata`() = runBlocking {
        val repo = repository(
            interleaveAfterRead {
                assertEquals(1, dao.applyKeyRotation("g1", 1, """["creator"]""", "{}"))
            }
        )
        assertTrue(
            repo.updateFromMeta(
                "g1", "Historical title", listOf("creator", "bob"), listOf("wss://new"), 600,
                memberNames = mapOf("creator" to "Creator", "bob" to "Bob"), description = "Historical description",
                eventId = "meta", expectedKeyEpoch = 0
            )
        )
        val row = dao.getById("g1")!!
        assertEquals(1, row.keyEpoch)
        assertEquals("""["creator"]""", row.members)
        assertEquals("""{"creator":"Creator"}""", row.memberNames)
        assertEquals("Historical title", row.name)
        assertEquals("Historical description", row.description)
        assertEquals(600L, row.lastMetaTimestamp)
    }

    @Test
    fun `creator update racing self rename retries without overwriting the newer name`() = runBlocking {
        val repo = repository(
            interleaveAfterRead {
                assertTrue(repository().applyMemberSelfUpdate("g1", "bob", 900, "self", false, "Self", 0))
            }
        )
        assertTrue(
            repo.updateFromMeta(
                "g1",
                "Renamed",
                listOf("creator", "bob"),
                emptyList(),
                600,
                memberNames = mapOf("bob" to "Creator"),
                eventId = "meta",
                expectedKeyEpoch = 0
            )
        )
        assertEquals(mapOf("bob" to "Self"), repo.getById("g1")!!.memberNames)
        assertEquals("""{"bob":"900:self"}""", dao.getById("g1")!!.memberClocks)
    }

    @Test
    fun `self rename racing creator metadata retries against the newer creator name`() = runBlocking {
        val repo = repository(
            interleaveAfterRead {
                assertTrue(
                    repository().updateFromMeta(
                        "g1",
                        "Renamed",
                        listOf("creator", "bob"),
                        emptyList(),
                        900,
                        memberNames = mapOf("bob" to "Creator"),
                        eventId = "meta",
                        expectedKeyEpoch = 0
                    )
                )
            }
        )
        assertTrue(repo.applyMemberSelfUpdate("g1", "bob", 600, "self", false, "Self", 0))
        assertEquals(mapOf("bob" to "Creator"), repo.getById("g1")!!.memberNames)
        assertEquals(900L, dao.getById("g1")!!.lastMetaTimestamp)
    }

    @Test
    fun `repository metadata can be applied inside a Room transaction`() = runBlocking {
        val repo = repository()
        db.withTransaction {
            assertTrue(repo.applyMemberSelfUpdate("g1", "bob", 600, "self", false, "Self", 0))
            assertTrue(
                repo.updateFromMeta(
                    "g1",
                    "Renamed",
                    listOf("creator", "bob"),
                    emptyList(),
                    700,
                    memberNames = mapOf("bob" to "Creator"),
                    eventId = "meta",
                    expectedKeyEpoch = 0
                )
            )
        }
        assertEquals(mapOf("bob" to "Creator"), repo.getById("g1")!!.memberNames)
    }

    @Test
    fun `same epoch revocation between self read and write cannot resurrect removed author`() = runBlocking {
        val repo = repository(
            interleaveAfterRead {
                dao.overrideMembership("g1", """["creator","bob2"]""", """{"bob2":"Bob"}""", "", 900, "revoke")
            }
        )
        assertFalse(repo.applyMemberSelfUpdate("g1", "bob", 1000, "self", true, "Bob", 0))
        assertEquals(listOf("creator", "bob2"), repo.getById("g1")!!.members)
        assertEquals(mapOf("bob2" to "Bob"), repo.getById("g1")!!.memberNames)
    }

    @Test
    fun `each snapshot guard rejects stale metadata and self writes atomically`() = runBlocking {
        val mismatches = listOf(
            entity.copy(keyEpoch = 1),
            entity.copy(members = """["creator"]"""),
            entity.copy(memberNames = "{}"),
            entity.copy(memberClocks = """{"bob":"700:self"}"""),
            entity.copy(lastMetaTimestamp = 501),
            entity.copy(lastMetaEventId = "newer"),
            entity.copy(createdBy = "new-creator")
        )
        for (snapshot in mismatches) {
            val metaRows = dao.updateMetaIfNewer(
                "g1", "Stale snapshot", """["removed"]""", "[]", "", 900, "{}", eventId = "meta",
                expectedKeyEpoch = snapshot.keyEpoch, expectedMembers = snapshot.members,
                expectedMemberNames = snapshot.memberNames, expectedMemberClocks = snapshot.memberClocks,
                expectedMetaTimestamp = snapshot.lastMetaTimestamp, expectedMetaEventId = snapshot.lastMetaEventId,
                expectedCreatedBy = snapshot.createdBy
            )
            assertEquals("meta snapshot=$snapshot", 0, metaRows)
            val selfRows = dao.updateMemberSelf(
                "g1", """["removed"]""", "{}", "{}",
                expectedKeyEpoch = snapshot.keyEpoch, expectedMembers = snapshot.members,
                expectedMemberNames = snapshot.memberNames, expectedMemberClocks = snapshot.memberClocks,
                expectedMetaTimestamp = snapshot.lastMetaTimestamp, expectedMetaEventId = snapshot.lastMetaEventId,
                expectedCreatedBy = snapshot.createdBy
            )
            assertEquals("self snapshot=$snapshot", 0, selfRows)
            assertEquals(entity, dao.getById("g1"))
        }
    }

    @Test
    fun `locally prepared rotation cannot drop a join that changed its source roster`() = runBlocking {
        val repo = repository()
        val sourceMembers = repo.getById("g1")!!.members
        assertTrue(repo.applyMemberSelfUpdate("g1", "carol", 600, "join", true, "Carol", 0))
        val joined = dao.getById("g1")!!
        assertFalse(repo.applyKeyRotation("g1", 1, listOf("creator"), emptyMap(), expectedMembers = sourceMembers))
        assertEquals(joined, dao.getById("g1"))
        assertTrue(
            repo.applyKeyRotation(
                "g1",
                1,
                listOf("creator", "carol"),
                emptyMap(),
                expectedMembers = listOf("creator", "bob", "carol")
            )
        )
        assertEquals(1, dao.getById("g1")!!.keyEpoch)
        assertEquals(listOf("creator", "carol"), repo.getById("g1")!!.members)
    }

    @Test
    fun `identity revocation tombstone survives repository recreation and blocks same epoch selfjoin`() = runBlocking {
        val repo = repository()
        assertTrue(repo.applyIdentityRevocation("g1", "bob", "bob2", 600, "revoke"))
        val revoked = dao.getById("g1")!!
        assertEquals(0, revoked.keyEpoch)
        assertEquals(listOf("creator", "bob2"), repo.getById("g1")!!.members)
        assertEquals(mapOf("bob2" to "Bob"), repo.getById("g1")!!.memberNames)
        assertEquals(
            // The durable checkpoint and facts replace the lossy seated marker.
            mapOf("revoked:bob" to "600:revoke", "replaced:bob" to "bob2"),
            Json.decodeFromString<Map<String, String>>(revoked.memberClocks)
        )
        val reopened = repository()
        assertFalse(reopened.applyMemberSelfUpdate("g1", "bob", 550, "delayed", true, "Old", 0))
        assertFalse(reopened.applyMemberSelfUpdate("g1", "bob", 900, "fresh", true, "Old", 0))
        assertEquals(revoked, dao.getById("g1"))
        assertTrue(reopened.applyIdentityRevocation("g1", "bob", "bob2", 600, "revoke"))
        assertEquals(revoked, dao.getById("g1"))
        assertTrue(reopened.applyMemberSelfUpdate("g1", "carol", 100, "join", true, null, 0))
        assertEquals(listOf("creator", "bob2", "carol"), reopened.getById("g1")!!.members)
    }

    @Test
    fun `new creator metas cannot restore a tombstoned member from their roster snapshot`() = runBlocking {
        val repo = repository()
        assertTrue(repo.applyIdentityRevocation("g1", "bob", "bob2", 600, "revoke"))
        assertTrue(
            repo.updateFromMeta(
                "g1", "Renamed", listOf("creator", "bob", "bob2"), emptyList(), 900,
                memberNames = mapOf("bob" to "Revoked", "bob2" to "Current"), eventId = "meta",
                expectedKeyEpoch = 0, expectedCreator = "creator"
            )
        )
        assertEquals(listOf("creator", "bob2"), repo.getById("g1")!!.members)
        assertEquals(mapOf("bob2" to "Current"), repo.getById("g1")!!.memberNames)
        assertFalse(repo.applyMemberSelfUpdate("g1", "bob", 1000, "join", true, "Old", 0))
    }

    @Test
    fun `creator revocation rejects previously authenticated meta before and during repository write`() = runBlocking {
        for (duringRead in listOf(false, true)) {
            dao.insert(entity)
            val change: suspend () -> Unit = {
                assertTrue(repository().applyIdentityRevocation("g1", "creator", "creator2", 600, "revoke"))
            }
            val repo = if (duringRead) {
                repository(interleaveAfterRead(change))
            } else {
                change()
                repository()
            }
            assertFalse(
                repo.updateFromMeta(
                    "g1", "Untrusted old creator", listOf("creator", "bob"), emptyList(), 900,
                    createdBy = "creator", memberNames = mapOf("bob" to "Overwritten"), eventId = "meta",
                    expectedKeyEpoch = 0, expectedCreator = "creator"
                )
            )
            val row = dao.getById("g1")!!
            assertEquals("creator2", row.createdBy)
            assertEquals(listOf("creator2", "bob"), repo.getById("g1")!!.members)
            assertEquals("Trip", row.name)
            assertEquals(600L, row.lastMetaTimestamp)
        }
    }

    @Test
    fun `identity revocation retries concurrent join and rename without dropping either`() = runBlocking {
        val repo = repository(
            interleaveAfterRead {
                assertTrue(repository().applyMemberSelfUpdate("g1", "carol", 700, "join", true, "Carol", 0))
                assertTrue(repository().applyMemberSelfUpdate("g1", "bob", 800, "name", false, "Robert", 0))
            }
        )
        assertTrue(repo.applyIdentityRevocation("g1", "bob", "bob2", 900, "revoke"))
        assertEquals(listOf("creator", "bob2", "carol"), repo.getById("g1")!!.members)
        assertEquals(mapOf("bob2" to "Robert", "carol" to "Carol"), repo.getById("g1")!!.memberNames)
        val clocks = Json.decodeFromString<Map<String, String>>(dao.getById("g1")!!.memberClocks)
        assertEquals("700:join", clocks["carol"])
        assertEquals("900:revoke", clocks["revoked:bob"])
    }

    @Test
    fun `identity revocation keeps existing replacement name and transfers creator without duplicates`() = runBlocking {
        dao.insert(entity.copy(memberNames = """{"creator":"Creator","bob":"Bob"}"""))
        val repo = repository()
        assertTrue(repo.applyIdentityRevocation("g1", "creator", "bob", 600, "revoke"))
        assertEquals("bob", repo.getById("g1")!!.createdBy)
        assertEquals(listOf("bob"), repo.getById("g1")!!.members)
        assertEquals(mapOf("bob" to "Bob"), repo.getById("g1")!!.memberNames)
    }

    @Test
    fun `revocation missing old identity installs tombstone when replacement already projected`() = runBlocking {
        dao.insert(entity.copy(members = """["creator","bob2"]""", memberNames = """{"bob2":"Bobby"}"""))
        val repo = repository()
        assertTrue(repo.applyIdentityRevocation("g1", "bob", "bob2", 600, "revoke"))
        assertFalse(repo.applyMemberSelfUpdate("g1", "bob", 900, "join", true, "Old", 0))
        assertEquals(listOf("creator", "bob2"), repo.getById("g1")!!.members)
        assertEquals(mapOf("bob2" to "Bobby"), repo.getById("g1")!!.memberNames)
    }

    @Test
    fun `identity removal without replacement clears creator authority and preserves newer watermark`() = runBlocking {
        dao.insert(entity.copy(lastMetaTimestamp = 900, lastMetaEventId = "zzz"))
        val repo = repository()
        assertTrue(repo.applyIdentityRevocation("g1", "creator", "", 900, "aaa"))
        val row = dao.getById("g1")!!
        assertEquals("", row.createdBy)
        assertEquals("zzz", row.lastMetaEventId)
        assertEquals(listOf("bob"), repo.getById("g1")!!.members)
        assertFalse(repo.applyMemberSelfUpdate("g1", "creator", 1000, "join", true, null, 0))
    }

    @Test
    fun `creator bootstrap fills only unknown creator and never restores revoked original identity`() = runBlocking {
        val repo = repository()
        repo.updateCreator("g1", "imposter", 10)
        assertEquals(entity, dao.getById("g1"))
        assertTrue(repo.applyIdentityRevocation("g1", "creator", "", 600, "revoke"))
        val revoked = dao.getById("g1")!!
        assertEquals("", revoked.createdBy)
        repo.updateCreator("g1", "creator", 1000)
        assertEquals(revoked, dao.getById("g1"))
        assertFalse(
            repo.updateFromMeta(
                "g1", "Old creator", listOf("creator", "bob"), emptyList(), 900,
                createdBy = "creator", eventId = "meta", expectedKeyEpoch = 0, expectedCreator = "creator"
            )
        )
        assertEquals(revoked, dao.getById("g1"))
        dao.insert(entity.copy(createdBy = ""))
        repo.updateCreator("g1", "creator", 1000)
        assertEquals(entity, dao.getById("g1"))
    }

    @Test
    fun `creator bootstrap loses race to revocation tombstone without restoring authority`() = runBlocking {
        dao.insert(entity.copy(createdBy = ""))
        val repo = repository(
            interleaveAfterRead {
                assertTrue(repository().applyIdentityRevocation("g1", "creator", "", 600, "revoke"))
            }
        )
        repo.updateCreator("g1", "creator", 1000)
        val row = dao.getById("g1")!!
        assertEquals("", row.createdBy)
        assertEquals(listOf("bob"), repo.getById("g1")!!.members)
        assertEquals("600:revoke", Json.decodeFromString<Map<String, String>>(row.memberClocks)["revoked:creator"])
    }

    /**
     * A rotation based on a pre-revocation roster must resolve bob to his recorded successor and
     * advance the epoch. This exercises repository projection, not signature verification.
     */
    @Test
    fun `rotation naming a tombstoned identity installs its recorded successor and still advances`() = runBlocking {
        val repo = repository()
        assertTrue(repo.applyIdentityRevocation("g1", "bob", "bob2", 600, "revoke"))
        assertTrue(repo.applyKeyRotation("g1", 1, listOf("creator", "bob"), mapOf("bob" to "Old")))
        assertEquals(1, dao.getById("g1")!!.keyEpoch)
        assertEquals(listOf("creator", "bob2"), repo.getById("g1")!!.members)
        assertEquals(mapOf("bob2" to "Old"), repo.getById("g1")!!.memberNames)
        assertEquals(listOf("creator", "bob2"), repo.resolveRoster("g1", listOf("creator", "bob", "bob2")))
        // A revoked identity without a successor drops out; an unknown one is untouched.
        assertTrue(repo.applyIdentityRevocation("g1", "bob2", "", 700, "revoke2"))
        assertEquals(listOf("creator", "dave"), repo.resolveRoster("g1", listOf("creator", "bob", "bob2", "dave")))
        assertTrue(repo.applyKeyRotation("g1", 2, listOf("creator", "bob", "dave"), mapOf("bob" to "Old")))
        assertEquals(listOf("creator", "dave"), repo.getById("g1")!!.members)
        assertEquals(emptyMap<String, String>(), repo.getById("g1")!!.memberNames)
    }

    @Test
    fun `rotation racing revocation resolves the identity revoked after the rotation read`() = runBlocking {
        val repo = repository(
            interleaveAfterRead {
                assertTrue(repository().applyIdentityRevocation("g1", "bob", "bob2", 600, "revoke"))
            }
        )
        assertTrue(repo.applyKeyRotation("g1", 1, listOf("creator", "bob"), mapOf("bob" to "Old")))
        val row = dao.getById("g1")!!
        assertEquals(1, row.keyEpoch)
        assertEquals(listOf("creator", "bob2"), repo.getById("g1")!!.members)
        assertEquals("600:revoke", Json.decodeFromString<Map<String, String>>(row.memberClocks)["revoked:bob"])
    }

    @Test
    fun `own journaled revocation records a tombstone when neither identity is in the roster`() = runBlocking {
        val repo = repository()
        assertFalse(repo.applyIdentityRevocation("g1", "carol", "carol2", 600, "revoke"))
        assertEquals(entity, dao.getById("g1"))
        assertTrue(repo.applyIdentityRevocation("g1", "carol", "carol2", 600, "revoke", allowAbsent = true))
        val row = dao.getById("g1")!!
        assertEquals(listOf("creator", "bob"), repo.getById("g1")!!.members)
        assertEquals("creator", row.createdBy)
        assertEquals(600L, row.lastMetaTimestamp)
        assertEquals("600:revoke", Json.decodeFromString<Map<String, String>>(row.memberClocks)["revoked:carol"])
        assertFalse(repo.applyMemberSelfUpdate("g1", "carol", 900, "join", true, "Carol", 0))
        // Replaying the journaled projection is a no-op, and carol2 may still join on its own.
        assertTrue(repo.applyIdentityRevocation("g1", "carol", "carol2", 600, "revoke", allowAbsent = true))
        assertEquals(row, dao.getById("g1"))
        assertTrue(repo.applyMemberSelfUpdate("g1", "carol2", 950, "join2", true, null, 0))
    }
}
