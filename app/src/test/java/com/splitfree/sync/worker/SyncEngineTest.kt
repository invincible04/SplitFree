package com.splitfree.sync.worker

import com.splitfree.data.identity.IdentityManager
import com.splitfree.data.local.dao.EventDao
import com.splitfree.data.local.dao.OutboxDao
import com.splitfree.data.local.entities.OutboxEntity
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.sync.event.EventProcessor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SyncEngineTest {
    private val eventDao = mockk<EventDao>(relaxed = true)
    private val outboxDao = mockk<OutboxDao>(relaxed = true)
    private val groupRepo = mockk<GroupRepository>(relaxed = true)
    private val nostrClient = mockk<NostrClient>()
    private val identity = mockk<IdentityManager>()
    private val eventProcessor = mockk<EventProcessor>()
    private lateinit var engine: SyncEngine

    private val groupId = "g1"
    private val groupKey = "key1"
    private val myPub = "aa".repeat(32)

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { identity.getPublicKeyHex() } returns myPub
        engine = SyncEngine(eventDao, outboxDao, groupRepo, nostrClient, identity, eventProcessor)
    }

    @After
    fun teardown() = unmockkAll()

    @Test
    fun `pullEvents skips already-known events`() = runBlocking {
        val event = NostrEvent(id = "e1", pubkey = myPub, createdAt = 100, kind = 30078, content = "x", sig = "s")
        coEvery { nostrClient.fetchEvents(groupId, 0, myPub) } returns listOf(event)
        coEvery { eventDao.getEventIds(groupId) } returns listOf("e1") // already known

        val count = engine.pullEvents(groupId, 0, groupKey)
        assertEquals(0, count)
        coVerify(exactly = 0) { eventProcessor.process(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `pullEvents processes new events and returns count`() = runBlocking {
        val event = NostrEvent(id = "e1", pubkey = myPub, createdAt = 100, kind = 30078, content = "x", sig = "s")
        coEvery { nostrClient.fetchEvents(groupId, 0, myPub) } returns listOf(event)
        coEvery { eventDao.getEventIds(groupId) } returns emptyList()
        coEvery { eventProcessor.process(any(), any(), any(), any(), any()) } returns
            com.splitfree.sync.event.EventProcessor.ProcessResult(
                stored = true,
                eventType = "expense",
                authorHex = myPub,
                groupName = "Test"
            )

        val count = engine.pullEvents(groupId, 0, groupKey)
        assertEquals(1, count)
        coVerify { groupRepo.updateLastSync(groupId, any()) }
    }

    @Test
    fun `pullEvents does not update lastSync when no new events`() = runBlocking {
        coEvery { nostrClient.fetchEvents(groupId, 0, myPub) } returns emptyList()
        coEvery { eventDao.getEventIds(groupId) } returns emptyList()

        engine.pullEvents(groupId, 0, groupKey)
        coVerify(exactly = 0) { groupRepo.updateLastSync(any(), any()) }
    }

    @Test
    fun `flushOutbox publishes and deletes successful events`() = runBlocking {
        val pending = listOf(OutboxEntity("e1", """{"id":"e1"}""", 100))
        coEvery { outboxDao.getAll() } returns pending
        coEvery { nostrClient.publishJson(any()) } returns true

        val count = engine.flushOutbox()
        assertEquals(1, count)
        coVerify { outboxDao.delete("e1") }
    }

    @Test
    fun `flushOutbox increments retry on failure`() = runBlocking {
        val pending = listOf(OutboxEntity("e1", """{"id":"e1"}""", 100))
        coEvery { outboxDao.getAll() } returns pending
        coEvery { nostrClient.publishJson(any()) } returns false

        val count = engine.flushOutbox()
        assertEquals(0, count)
        coVerify { outboxDao.incrementRetry("e1", any()) }
    }

    @Test
    fun `flushOutbox returns 0 when outbox is empty`() = runBlocking {
        coEvery { outboxDao.getAll() } returns emptyList()
        assertEquals(0, engine.flushOutbox())
    }
}
