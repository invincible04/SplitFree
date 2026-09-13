package com.splitfree.data.local.dao

import android.app.Application
import androidx.room.Room
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.local.entities.EventEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Exercises the real SQL behind the event store against an in-memory Room database, with a focus
 * on the `applyState` split: projection queries must only see applied rows, identity / dedup
 * lookups must see everything, and evidence upgrades must only touch `seal:` placeholders.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class EventDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: EventDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.eventDao()
    }

    @After
    fun tearDown() = db.close()

    private fun event(
        id: String,
        groupId: String = "g1",
        pubkey: String = "alice",
        createdAt: Long = 100,
        eventType: String = "expense",
        expenseUuid: String? = null,
        sig: String = "sig-$id",
        originalEventJson: String? = """{"id":"$id"}""",
        applyState: Int = EventEntity.APPLY_STATE_APPLIED
    ) = EventEntity(
        eventId = id,
        groupId = groupId,
        pubkey = pubkey,
        createdAt = createdAt,
        kind = 30078,
        contentEncrypted = "cipher-$id",
        eventType = eventType,
        expenseUuid = expenseUuid,
        sig = sig,
        receivedAt = createdAt + 1,
        originalEventJson = originalEventJson,
        applyState = applyState
    )

    // --- insert / identity ---

    @Test
    fun `insertIfNew dedups on eventId regardless of applyState`() = runBlocking {
        assertTrue(dao.insertIfNew(event("e1", applyState = EventEntity.APPLY_STATE_PENDING)))
        assertFalse(dao.insertIfNew(event("e1")))

        assertEquals(EventEntity.APPLY_STATE_PENDING, dao.getEvent("e1")!!.applyState)
    }

    @Test
    fun `getEvent and getEventIds see pending rows`() = runBlocking {
        dao.insert(event("applied"))
        dao.insert(event("pending", applyState = EventEntity.APPLY_STATE_PENDING))

        assertNotNull(dao.getEvent("pending"))
        assertEquals(setOf("applied", "pending"), dao.getEventIds("g1").toSet())
    }

    // --- applyState ---

    @Test
    fun `getEventsByGroup and observeEventsByGroup exclude pending rows`() = runBlocking {
        dao.insert(event("a", createdAt = 100))
        dao.insert(event("pending", createdAt = 150, applyState = EventEntity.APPLY_STATE_PENDING))
        dao.insert(event("b", createdAt = 200))
        dao.insert(event("other-group", groupId = "g2"))

        assertEquals(listOf("a", "b"), dao.getEventsByGroup("g1").map { it.eventId })
        assertEquals(listOf("a", "b"), dao.observeEventsByGroup("g1").first().map { it.eventId })
    }

    @Test
    fun `getEventCount only counts applied rows`() = runBlocking {
        dao.insert(event("a"))
        dao.insert(event("b"))
        dao.insert(event("pending", applyState = EventEntity.APPLY_STATE_PENDING))

        assertEquals(2, dao.getEventCount("g1"))
    }

    @Test
    fun `getPendingEvents returns only pending rows in deterministic order`() = runBlocking {
        dao.insert(event("applied"))
        dao.insert(event("p-late", createdAt = 300, applyState = EventEntity.APPLY_STATE_PENDING))
        dao.insert(event("p-b", createdAt = 100, applyState = EventEntity.APPLY_STATE_PENDING))
        dao.insert(event("p-a", createdAt = 100, applyState = EventEntity.APPLY_STATE_PENDING))
        dao.insert(event("p-other", groupId = "g2", applyState = EventEntity.APPLY_STATE_PENDING))

        assertEquals(listOf("p-a", "p-b", "p-late"), dao.getPendingEvents("g1").map { it.eventId })
        assertEquals(listOf("p-other"), dao.getPendingEvents("g2").map { it.eventId })
    }

    @Test
    fun `setApplyState flips a row between pending and applied`() = runBlocking {
        dao.insert(event("e", applyState = EventEntity.APPLY_STATE_PENDING))
        assertEquals(emptyList<EventEntity>(), dao.getEventsByGroup("g1"))

        dao.setApplyState("e", EventEntity.APPLY_STATE_APPLIED)

        assertEquals(EventEntity.APPLY_STATE_APPLIED, dao.getEvent("e")!!.applyState)
        assertEquals(listOf("e"), dao.getEventsByGroup("g1").map { it.eventId })
        assertEquals(emptyList<EventEntity>(), dao.getPendingEvents("g1"))

        dao.setApplyState("e", EventEntity.APPLY_STATE_PENDING)

        assertEquals(emptyList<EventEntity>(), dao.getEventsByGroup("g1"))
        assertEquals(listOf("e"), dao.getPendingEvents("g1").map { it.eventId })
    }

    @Test
    fun `getLatestEventByType skips pending rows`() = runBlocking {
        dao.insert(event("meta-old", createdAt = 100, eventType = "group_meta"))
        dao.insert(
            event(
                "meta-pending",
                createdAt = 200,
                eventType = "group_meta",
                applyState = EventEntity.APPLY_STATE_PENDING
            )
        )
        dao.insert(event("expense", createdAt = 300))

        assertEquals("meta-old", dao.getLatestEventByType("g1", "group_meta")!!.eventId)
        assertNull(dao.getLatestEventByType("g1", "snapshot"))
    }

    @Test
    fun `expense lookups by uuid skip pending rows and pick the earliest applied one`() = runBlocking {
        dao.insert(event("pending", createdAt = 50, expenseUuid = "u", applyState = EventEntity.APPLY_STATE_PENDING))
        dao.insert(event("later", createdAt = 200, expenseUuid = "u"))
        dao.insert(event("earlier", createdAt = 100, expenseUuid = "u"))
        dao.insert(event("correction", createdAt = 10, expenseUuid = "u", eventType = "expense_correction"))

        assertEquals("earlier", dao.getExpenseByUuid("u", "g1")!!.eventId)
        assertNull(dao.getExpenseByUuid("u", "g2"))
    }

    @Test
    fun `getExpenseByAuthor binds the expense identity to its author`() = runBlocking {
        dao.insert(event("alice-expense", pubkey = "alice", createdAt = 100, expenseUuid = "u"))
        dao.insert(event("bob-expense", pubkey = "bob", createdAt = 50, expenseUuid = "u"))
        dao.insert(
            event(
                "bob-pending",
                pubkey = "bob",
                createdAt = 10,
                expenseUuid = "u",
                applyState = EventEntity.APPLY_STATE_PENDING
            )
        )

        assertEquals("alice-expense", dao.getExpenseByAuthor("u", "g1", "alice")!!.eventId)
        assertEquals("bob-expense", dao.getExpenseByAuthor("u", "g1", "bob")!!.eventId)
        assertNull(dao.getExpenseByAuthor("u", "g1", "carol"))
    }

    @Test
    fun `getDeletedExpenseUuids excludes pending deletes`() = runBlocking {
        dao.insert(event("d1", eventType = "expense_delete", expenseUuid = "u1"))
        dao.insert(
            event("d2", eventType = "expense_delete", expenseUuid = "u2", applyState = EventEntity.APPLY_STATE_PENDING)
        )
        dao.insert(event("d-null", eventType = "expense_delete", expenseUuid = null))
        dao.insert(event("not-delete", expenseUuid = "u3"))

        assertEquals(listOf("u1"), dao.getDeletedExpenseUuids("g1"))
    }

    @Test
    fun `admission tombstones count only applied deletes of the author`() = runBlocking {
        dao.insert(event("applied", eventType = "expense_delete", expenseUuid = "u1"))
        dao.insert(
            event(
                "pending",
                eventType = "expense_delete",
                expenseUuid = "u2",
                applyState = EventEntity.APPLY_STATE_PENDING
            )
        )
        dao.insert(
            event(
                "failed",
                eventType = "expense_delete",
                expenseUuid = "u3",
                applyState = EventEntity.APPLY_STATE_FAILED
            )
        )
        dao.insert(event("bob", pubkey = "bob", eventType = "expense_delete", expenseUuid = "u4"))
        dao.insert(event("other-group", groupId = "g2", eventType = "expense_delete", expenseUuid = "u5"))
        dao.insert(event("no-uuid", eventType = "expense_delete"))
        dao.insert(event("not-delete", expenseUuid = "u6"))

        assertEquals(listOf("u1"), dao.getAppliedDeletedExpenseUuidsByAuthor("g1", "alice"))
        assertEquals(listOf("u4"), dao.getAppliedDeletedExpenseUuidsByAuthor("g1", "bob"))
        assertTrue(dao.getAppliedDeletedExpenseUuidsByAuthor("g1", "carol").isEmpty())
        assertEquals(listOf("u1", "u4"), dao.getDeletedExpenseUuids("g1"))
        assertEquals(
            setOf("applied", "bob", "no-uuid", "not-delete"),
            dao.observeEventsByGroup("g1").first().map { it.eventId }.toSet()
        )
    }

    @Test
    fun `original insert applies only pending deletes of the same group author and uuid`() = runBlocking {
        val pending = event(
            "delete",
            expenseUuid = "u",
            eventType = "expense_delete",
            applyState = EventEntity.APPLY_STATE_PENDING
        )
        val untouched = listOf(
            pending.copy(eventId = "other-author", pubkey = "bob"),
            pending.copy(eventId = "other-group", groupId = "g2"),
            pending.copy(eventId = "other-uuid", expenseUuid = "v"),
            pending.copy(eventId = "failed", applyState = EventEntity.APPLY_STATE_FAILED),
            pending.copy(eventId = "correction", eventType = "expense_correction")
        )
        (listOf(pending) + untouched).forEach { dao.insert(it) }
        assertTrue(dao.insert(event("original", expenseUuid = "u")) != -1L)
        assertEquals(EventEntity.APPLY_STATE_APPLIED, dao.getEvent("delete")!!.applyState)
        untouched.forEach { assertEquals(it, dao.getEvent(it.eventId)) }
        assertEquals(-1L, dao.insert(event("original", expenseUuid = "u")))
        assertEquals(setOf("original", "delete"), dao.getEventsByGroup("g1").map { it.eventId }.toSet())
    }

    @Test
    fun `interleaved second original after promotion is rejected distinctly from a duplicate`() = runBlocking {
        // Both originals passed the pre-transaction tombstone check while the delete was still pending.
        dao.insert(
            event(
                "delete",
                expenseUuid = "u",
                eventType = "expense_delete",
                applyState = EventEntity.APPLY_STATE_PENDING
            )
        )
        assertTrue(dao.insert(event("first", expenseUuid = "u")) != -1L)
        assertEquals(EventEntity.APPLY_STATE_APPLIED, dao.getEvent("delete")!!.applyState)

        assertEquals(EventDao.REJECTED_DELETED, dao.insert(event("second", expenseUuid = "u", createdAt = 200)))
        assertNull(dao.getEvent("second"))
        assertEquals(-1L, dao.insert(event("first", expenseUuid = "u")))
        // Deleted history from a backup is not a replay: import stores it through insertHistory.
        assertTrue(dao.insertHistory(event("second", expenseUuid = "u", createdAt = 200)) != -1L)
        assertEquals(-1L, dao.insertHistory(event("second", expenseUuid = "u", createdAt = 200)))
        assertEquals(setOf("delete", "first", "second"), dao.getEventsByGroup("g1").map { it.eventId }.toSet())
        // Other authors, uuids and groups are unaffected by alice's applied tombstone.
        assertTrue(dao.insert(event("bob", pubkey = "bob", expenseUuid = "u")) != -1L)
        assertTrue(dao.insert(event("other-uuid", expenseUuid = "v")) != -1L)
        assertTrue(dao.insert(event("other-group", groupId = "g2", expenseUuid = "u")) != -1L)
    }

    @Test
    fun `delete inserted after its original committed is applied immediately`() = runBlocking {
        // The processor saw no original, then a concurrent import or publish committed it.
        dao.insert(event("original", expenseUuid = "u"))
        val delete =
            event(
                "delete",
                expenseUuid = "u",
                eventType = "expense_delete",
                applyState = EventEntity.APPLY_STATE_PENDING
            )

        assertTrue(dao.insert(delete) != -1L)

        assertEquals(EventEntity.APPLY_STATE_APPLIED, dao.getEvent("delete")!!.applyState)
        assertEquals(0, dao.countPending("g1"))
        assertEquals(listOf("u"), dao.getDeletedExpenseUuids("g1"))
        // Without an applied same-author original the delete still waits.
        assertTrue(dao.insert(delete.copy(eventId = "bob-delete", pubkey = "bob")) != -1L)
        assertTrue(dao.insert(delete.copy(eventId = "other-uuid", expenseUuid = "v")) != -1L)
        assertTrue(dao.insert(delete.copy(eventId = "correction", eventType = "expense_correction")) != -1L)
        assertEquals(
            setOf("bob-delete", "other-uuid", "correction"),
            dao.getPendingEvents("g1").map {
                it.eventId
            }.toSet()
        )
    }

    @Test
    fun `pending failed and duplicate original inserts do not promote deletes`() = runBlocking {
        val delete = event(
            "delete",
            expenseUuid = "u",
            eventType = "expense_delete",
            applyState = EventEntity.APPLY_STATE_PENDING
        )
        dao.insert(delete)
        assertTrue(dao.insert(event("pending", expenseUuid = "u", applyState = EventEntity.APPLY_STATE_PENDING)) != -1L)
        assertTrue(dao.insert(event("failed", expenseUuid = "u", applyState = EventEntity.APPLY_STATE_FAILED)) != -1L)
        assertEquals(-1L, dao.insert(event("pending", expenseUuid = "u")))
        assertEquals(delete, dao.getEvent(delete.eventId))
        assertTrue(dao.getEventsByGroup("g1").isEmpty())
    }

    @Test
    fun `getEventsByType returns every row of that type regardless of applyState`() = runBlocking {
        dao.insert(event("m2", createdAt = 200, eventType = "group_meta"))
        dao.insert(
            event("m1-pending", createdAt = 100, eventType = "group_meta", applyState = EventEntity.APPLY_STATE_PENDING)
        )
        dao.insert(event("m1", createdAt = 100, eventType = "group_meta"))
        dao.insert(event("expense"))

        assertEquals(listOf("m1", "m1-pending", "m2"), dao.getEventsByType("g1", "group_meta").map { it.eventId })
    }

    // --- evidence ---

    @Test
    fun `getEventEvidence lists applied and pending rows but never permanently failed ones`() = runBlocking {
        dao.insert(event("a", sig = "sig-a"))
        dao.insert(event("seal", sig = "seal:abc"))
        dao.insert(event("pending", sig = "sig-p", applyState = EventEntity.APPLY_STATE_PENDING))
        dao.insert(event("failed", sig = "sig-f", applyState = EventEntity.APPLY_STATE_FAILED))
        dao.insert(event("other", groupId = "g2"))

        assertEquals(
            setOf(EventEvidence("a", "sig-a"), EventEvidence("seal", "seal:abc"), EventEvidence("pending", "sig-p")),
            dao.getEventEvidence("g1").toSet()
        )
    }

    @Test
    fun `countPending counts only rows still waiting for their effect`() = runBlocking {
        dao.insert(event("a"))
        dao.insert(event("p1", applyState = EventEntity.APPLY_STATE_PENDING))
        dao.insert(event("p2", applyState = EventEntity.APPLY_STATE_PENDING))
        dao.insert(event("f", applyState = EventEntity.APPLY_STATE_FAILED))
        dao.insert(event("other", groupId = "g2", applyState = EventEntity.APPLY_STATE_PENDING))

        assertEquals(2, dao.countPending("g1"))
        dao.setApplyState("p1", EventEntity.APPLY_STATE_APPLIED)
        assertEquals(1, dao.countPending("g1"))
    }

    @Test
    fun `upgradeEvidence replaces a seal placeholder and returns 1`() = runBlocking {
        dao.insert(event("e", sig = "seal:placeholder", originalEventJson = null))

        val updated = dao.upgradeEvidence("e", "real-sig", """{"id":"e","sig":"real-sig"}""")

        assertEquals(1, updated)
        val row = dao.getEvent("e")!!
        assertEquals("real-sig", row.sig)
        assertEquals("""{"id":"e","sig":"real-sig"}""", row.originalEventJson)
    }

    @Test
    fun `upgradeEvidence leaves a signed row alone and returns 0`() = runBlocking {
        val signed = event("e", sig = "real-sig", originalEventJson = """{"id":"e"}""")
        dao.insert(signed)

        val updated = dao.upgradeEvidence("e", "attacker-sig", """{"id":"e","sig":"attacker-sig"}""")

        assertEquals(0, updated)
        assertEquals(signed, dao.getEvent("e"))
    }

    @Test
    fun `upgradeEvidence returns 0 for an unknown event`() = runBlocking {
        assertEquals(0, dao.upgradeEvidence("missing", "sig", "{}"))
    }
}
