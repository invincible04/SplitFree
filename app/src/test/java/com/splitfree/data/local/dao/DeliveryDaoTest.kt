package com.splitfree.data.local.dao

import android.app.Application
import androidx.room.Room
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.local.entities.DeliveryEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Runs the envelope carriage queries against a real in-memory Room database so the SQL that
 * decides dedup, consumption and courier eviction is what gets verified.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DeliveryDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: DeliveryDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.deliveryDao()
    }

    @After
    fun tearDown() = db.close()

    private fun row(
        id: String,
        groupId: String = "g1",
        recipient: String = "bob",
        state: Int = DeliveryEntity.STATE_AVAILABLE,
        source: Int = DeliveryEntity.SOURCE_CARRIED,
        createdAt: Long = 100,
        receivedAt: Long = createdAt,
        sizeBytes: Int = 10,
        eventType: String = DeliveryEntity.TYPE_GIFT_WRAP
    ) = DeliveryEntity(
        envelopeId = id,
        groupId = groupId,
        recipient = recipient,
        eventId = "inner-$id",
        envelopeJson = if (state == DeliveryEntity.STATE_AVAILABLE) """{"id":"$id"}""" else null,
        eventType = eventType,
        state = state,
        source = source,
        createdAt = createdAt,
        receivedAt = receivedAt,
        sizeBytes = sizeBytes
    )

    // --- insert / get ---

    @Test
    fun `insertIfNew stores the row and reports true`() = runBlocking {
        val d = row("a")

        assertTrue(dao.insertIfNew(d))
        assertEquals(d, dao.get("a"))
    }

    @Test
    fun `insertIfNew dedups on envelopeId and keeps the first row`() = runBlocking {
        val first = row("a", recipient = "bob", sizeBytes = 10)

        assertTrue(dao.insertIfNew(first))
        assertFalse(dao.insertIfNew(first.copy(recipient = "mallory", sizeBytes = 999)))

        assertEquals(first, dao.get("a"))
        assertEquals(1, dao.countAvailable("g1"))
    }

    @Test
    fun `get returns null for an unknown envelope`() = runBlocking {
        assertNull(dao.get("missing"))
    }

    // --- available / consumed ---

    @Test
    fun `getAvailable excludes consumed rows and other groups, oldest first`() = runBlocking {
        dao.insert(row("newer", createdAt = 300))
        dao.insert(row("older", createdAt = 100))
        dao.insert(row("tie-b", createdAt = 200))
        dao.insert(row("tie-a", createdAt = 200))
        dao.insert(row("consumed", createdAt = 50, state = DeliveryEntity.STATE_CONSUMED))
        dao.insert(row("other-group", groupId = "g2", createdAt = 10))

        assertEquals(listOf("older", "tie-a", "tie-b", "newer"), dao.getAvailable("g1").map { it.envelopeId })
        assertEquals(listOf("other-group"), dao.getAvailable("g2").map { it.envelopeId })
    }

    @Test
    fun `getEnvelopeIds returns every known id including consumed tombstones`() = runBlocking {
        dao.insert(row("a"))
        dao.insert(row("b", state = DeliveryEntity.STATE_CONSUMED))
        dao.insert(row("c", groupId = "g2"))

        assertEquals(setOf("a", "b"), dao.getEnvelopeIds("g1").toSet())
        assertEquals(listOf("c"), dao.getEnvelopeIds("g2"))
    }

    @Test
    fun `markConsumed flips the state and drops the JSON but keeps the tombstone`() = runBlocking {
        dao.insert(row("a"))
        dao.insert(row("b"))

        dao.markConsumed("a")

        val a = dao.get("a")!!
        assertEquals(DeliveryEntity.STATE_CONSUMED, a.state)
        assertNull(a.envelopeJson)
        assertEquals("inner-a", a.eventId) // hint survives
        assertEquals(listOf("b"), dao.getAvailable("g1").map { it.envelopeId })
        assertEquals(setOf("a", "b"), dao.getEnvelopeIds("g1").toSet())
        // Re-offering a consumed envelope is a no-op.
        assertFalse(dao.insertIfNew(row("a")))
        assertNull(dao.get("a")!!.envelopeJson)
    }

    @Test
    fun `countAvailable counts authored and carried rows that still hold JSON`() = runBlocking {
        dao.insert(row("authored", source = DeliveryEntity.SOURCE_AUTHORED))
        dao.insert(row("carried", source = DeliveryEntity.SOURCE_CARRIED))
        dao.insert(row("consumed", state = DeliveryEntity.STATE_CONSUMED))
        dao.insert(row("other", groupId = "g2"))

        assertEquals(2, dao.countAvailable("g1"))
        assertEquals(1, dao.countAvailable("g2"))
        assertEquals(0, dao.countAvailable("g3"))
    }

    // --- courier budget ---

    @Test
    fun `countCarried and carriedBytes only count carried available rows`() = runBlocking {
        dao.insert(row("carried-1", source = DeliveryEntity.SOURCE_CARRIED, sizeBytes = 10))
        dao.insert(row("carried-2", source = DeliveryEntity.SOURCE_CARRIED, sizeBytes = 25))
        dao.insert(row("authored", source = DeliveryEntity.SOURCE_AUTHORED, sizeBytes = 1000))
        dao.insert(
            row(
                "carried-consumed",
                source = DeliveryEntity.SOURCE_CARRIED,
                state = DeliveryEntity.STATE_CONSUMED,
                sizeBytes = 500
            )
        )
        dao.insert(row("other-group", groupId = "g2", source = DeliveryEntity.SOURCE_CARRIED, sizeBytes = 77))

        assertEquals(2, dao.countCarried("g1"))
        assertEquals(35L, dao.carriedBytes("g1"))
        assertEquals(1, dao.countCarried("g2"))
        assertEquals(77L, dao.carriedBytes("g2"))
    }

    @Test
    fun `carriedBytes is zero not null for an empty group`() = runBlocking {
        assertEquals(0, dao.countCarried("empty"))
        assertEquals(0L, dao.carriedBytes("empty"))
    }

    @Test
    fun `evictOldestCarried removes the n earliest-received carried rows only`() = runBlocking {
        dao.insert(row("r3", receivedAt = 300))
        dao.insert(row("r1", receivedAt = 100))
        dao.insert(row("r2", receivedAt = 200))
        dao.insert(row("r4", receivedAt = 400))
        dao.insert(row("authored-old", source = DeliveryEntity.SOURCE_AUTHORED, receivedAt = 1))
        dao.insert(row("consumed-old", state = DeliveryEntity.STATE_CONSUMED, receivedAt = 2))
        dao.insert(row("other-group-old", groupId = "g2", receivedAt = 3))

        dao.evictOldestCarried("g1", 2)

        assertEquals(setOf("r3", "r4", "authored-old", "consumed-old"), dao.getEnvelopeIds("g1").toSet())
        assertEquals(listOf("other-group-old"), dao.getEnvelopeIds("g2"))
        assertEquals(2, dao.countCarried("g1"))
    }

    @Test
    fun `evictOldestCarried breaks receivedAt ties by envelopeId`() = runBlocking {
        dao.insert(row("b", receivedAt = 100))
        dao.insert(row("a", receivedAt = 100))
        dao.insert(row("c", receivedAt = 100))

        dao.evictOldestCarried("g1", 1)

        assertEquals(setOf("b", "c"), dao.getEnvelopeIds("g1").toSet())
    }

    @Test
    fun `evictOldestCarried with n larger than the carried set clears exactly the carried rows`() = runBlocking {
        dao.insert(row("carried"))
        dao.insert(row("authored", source = DeliveryEntity.SOURCE_AUTHORED))

        dao.evictOldestCarried("g1", 50)

        assertEquals(listOf("authored"), dao.getEnvelopeIds("g1"))
    }

    // --- retention ---

    @Test
    fun `deleteOlderThan removes rows received before the cutoff for the given source only`() = runBlocking {
        dao.insert(row("carried-old", source = DeliveryEntity.SOURCE_CARRIED, receivedAt = 10))
        dao.insert(row("carried-at-cutoff", source = DeliveryEntity.SOURCE_CARRIED, receivedAt = 100))
        dao.insert(row("carried-new", source = DeliveryEntity.SOURCE_CARRIED, receivedAt = 200))
        dao.insert(row("authored-old", source = DeliveryEntity.SOURCE_AUTHORED, receivedAt = 10))
        dao.insert(
            row(
                "carried-consumed-old",
                source = DeliveryEntity.SOURCE_CARRIED,
                state = DeliveryEntity.STATE_CONSUMED,
                receivedAt = 10
            )
        )
        dao.insert(row("other-group-old", groupId = "g2", source = DeliveryEntity.SOURCE_CARRIED, receivedAt = 10))

        dao.deleteOlderThan(100, DeliveryEntity.SOURCE_CARRIED)

        // Cutoff is exclusive; consumed tombstones and other groups share the same retention.
        assertEquals(setOf("carried-at-cutoff", "carried-new", "authored-old"), dao.getEnvelopeIds("g1").toSet())
        assertEquals(emptyList<String>(), dao.getEnvelopeIds("g2"))

        dao.deleteOlderThan(100, DeliveryEntity.SOURCE_AUTHORED)

        assertEquals(setOf("carried-at-cutoff", "carried-new"), dao.getEnvelopeIds("g1").toSet())
    }
}
