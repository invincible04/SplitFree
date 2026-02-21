package com.splitfree.data.repository

import com.splitfree.data.local.AppDatabase
import com.splitfree.data.local.dao.EventDao
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.domain.repository.EventSnapshot
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EventRepositoryTest {
    private val db = mockk<AppDatabase>()
    private val eventDao = mockk<EventDao>(relaxed = true)
    private lateinit var repo: EventRepository

    private val entity = EventEntity(
        eventId = "e1", groupId = "g1", pubkey = "pub1",
        createdAt = 1000L, kind = 30078, contentEncrypted = "enc",
        eventType = "expense", expenseUuid = "u1", sig = "sig1",
        receivedAt = 2000L, originalEventJson = "{}"
    )

    @Before
    fun setup() {
        repo = EventRepository(db, eventDao)
    }

    @Test
    fun `getEventsByGroup maps entities to snapshots`() = runTest {
        coEvery { eventDao.getEventsByGroup("g1") } returns listOf(entity)
        val result = repo.getEventsByGroup("g1")
        assertEquals(1, result.size)
        val s = result[0]
        assertEquals("e1", s.eventId)
        assertEquals("g1", s.groupId)
        assertEquals("pub1", s.pubkey)
        assertEquals(1000L, s.createdAt)
        assertEquals(30078, s.kind)
        assertEquals("enc", s.contentEncrypted)
        assertEquals("expense", s.eventType)
        assertEquals("u1", s.expenseUuid)
        assertEquals("sig1", s.sig)
        assertEquals(2000L, s.receivedAt)
        assertEquals("{}", s.originalEventJson)
    }

    @Test
    fun `observeEventsByGroup maps flow`() = runTest {
        every { eventDao.observeEventsByGroup("g1") } returns flowOf(listOf(entity))
        val result = repo.observeEventsByGroup("g1").first()
        assertEquals(1, result.size)
        assertEquals("e1", result[0].eventId)
    }

    @Test
    fun `getEventIds delegates`() = runTest {
        coEvery { eventDao.getEventIds("g1") } returns listOf("e1", "e2")
        assertEquals(listOf("e1", "e2"), repo.getEventIds("g1"))
    }

    @Test
    fun `getDeletedExpenseUuids delegates`() = runTest {
        coEvery { eventDao.getDeletedExpenseUuids("g1") } returns listOf("u1")
        assertEquals(listOf("u1"), repo.getDeletedExpenseUuids("g1"))
    }

    @Test
    fun `getExpenseByUuid returns mapped snapshot`() = runTest {
        coEvery { eventDao.getExpenseByUuid("u1", "g1") } returns entity
        val result = repo.getExpenseByUuid("u1", "g1")!!
        assertEquals("e1", result.eventId)
    }

    @Test
    fun `getExpenseByUuid returns null when not found`() = runTest {
        coEvery { eventDao.getExpenseByUuid("missing", "g1") } returns null
        assertNull(repo.getExpenseByUuid("missing", "g1"))
    }

    @Test
    fun `getLatestEventByType returns mapped snapshot`() = runTest {
        coEvery { eventDao.getLatestEventByType("g1", "snapshot") } returns entity
        assertEquals("e1", repo.getLatestEventByType("g1", "snapshot")!!.eventId)
    }

    @Test
    fun `getEventCount delegates`() = runTest {
        coEvery { eventDao.getEventCount("g1") } returns 42
        assertEquals(42, repo.getEventCount("g1"))
    }

    @Test
    fun `insert maps snapshot to entity`() = runTest {
        val snapshot = EventSnapshot(
            eventId = "e2", groupId = "g1", pubkey = "pub2",
            createdAt = 3000L, kind = 30078, contentEncrypted = "enc2",
            eventType = "settlement", sig = "sig2", receivedAt = 4000L
        )
        val slot = slot<EventEntity>()
        coEvery { eventDao.insert(capture(slot)) } returns 1L
        repo.insert(snapshot)
        assertEquals("e2", slot.captured.eventId)
        assertEquals("g1", slot.captured.groupId)
        assertEquals("settlement", slot.captured.eventType)
    }

    @Test
    fun `insertIfNew returns true for new event`() = runTest {
        coEvery { eventDao.insertIfNew(any()) } returns true
        val snapshot = EventSnapshot(
            eventId = "e3",
            groupId = "g1",
            pubkey = "pub1",
            createdAt = 1000L,
            contentEncrypted = "enc",
            eventType = "expense"
        )
        assertTrue(repo.insertIfNew(snapshot))
    }

    @Test
    fun `insertIfNew returns false for duplicate`() = runTest {
        coEvery { eventDao.insertIfNew(any()) } returns false
        val snapshot = EventSnapshot(
            eventId = "e1",
            groupId = "g1",
            pubkey = "pub1",
            createdAt = 1000L,
            contentEncrypted = "enc",
            eventType = "expense"
        )
        assertFalse(repo.insertIfNew(snapshot))
    }

    @Test
    fun `snapshot to entity preserves all fields including optional nulls`() = runTest {
        val snapshot = EventSnapshot(
            eventId = "e4", groupId = "g2", pubkey = "pub3",
            createdAt = 5000L, kind = 30078, contentEncrypted = "enc3",
            eventType = "group_meta", expenseUuid = null, sig = "sig3",
            receivedAt = 6000L, originalEventJson = null
        )
        val slot = slot<EventEntity>()
        coEvery { eventDao.insert(capture(slot)) } returns 1L
        repo.insert(snapshot)
        assertNull(slot.captured.expenseUuid)
        assertNull(slot.captured.originalEventJson)
    }
}
