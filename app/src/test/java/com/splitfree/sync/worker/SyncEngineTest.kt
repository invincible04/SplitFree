package com.splitfree.sync.worker

import com.splitfree.data.identity.IdentityManager
import com.splitfree.data.local.dao.EventDao
import com.splitfree.data.local.dao.OutboxDao
import com.splitfree.data.local.entities.OutboxEntity
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.sync.event.EventProcessor
import com.splitfree.sync.event.IngestionContext
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
        coEvery { eventProcessor.retryDeferred(any()) } returns 0
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
        coVerify(exactly = 0) { eventProcessor.process(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `pullEvents processes new events and returns count`() = runBlocking {
        val event = NostrEvent(id = "e1", pubkey = myPub, createdAt = 100, kind = 30078, content = "x", sig = "s")
        coEvery { nostrClient.fetchEvents(groupId, 0, myPub) } returns listOf(event)
        coEvery { eventDao.getEventIds(groupId) } returns emptyList()
        coEvery { eventProcessor.process(any(), any(), any(), any(), any(), any()) } returns
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
    fun `pullEvents processes a newest-first relay batch oldest-first`() = runBlocking {
        // Relays hand back newest-first; key_rotation must land in epoch order and group_meta is LWW.
        val newer = NostrEvent(id = "e2", pubkey = myPub, createdAt = 300, kind = 30078, content = "x", sig = "s")
        val older = NostrEvent(id = "e1", pubkey = myPub, createdAt = 100, kind = 30078, content = "x", sig = "s")
        coEvery { nostrClient.fetchEvents(groupId, 0, myPub) } returns listOf(newer, older)
        coEvery { eventDao.getEventIds(groupId) } returns emptyList()
        val processed = mutableListOf<String>()
        coEvery { eventProcessor.process(any(), any(), any(), any(), any(), any()) } answers {
            processed += firstArg<NostrEvent>().id
            com.splitfree.sync.event.EventProcessor.ProcessResult(stored = false)
        }

        engine.pullEvents(groupId, 0, groupKey)

        assertEquals(listOf("e1", "e2"), processed)
    }

    @Test
    fun `pullEvents ingests a live pull in LIVE context`() = runBlocking {
        val event = NostrEvent(id = "e1", pubkey = myPub, createdAt = 100, kind = 30078, content = "x", sig = "s")
        coEvery { nostrClient.fetchEvents(groupId, 0, myPub) } returns listOf(event)
        coEvery { eventDao.getEventIds(groupId) } returns emptyList()
        coEvery { eventProcessor.process(any(), any(), any(), any(), any(), any()) } returns
            EventProcessor.ProcessResult(stored = false)

        engine.pullEvents(groupId, 0, groupKey)

        coVerify {
            eventProcessor.process(event, groupId, null, false, false, IngestionContext.LIVE)
        }
    }

    @Test
    fun `pullEvents ingests a lenient full pull in RECONCILIATION context`() = runBlocking {
        val event = NostrEvent(id = "e1", pubkey = myPub, createdAt = 100, kind = 30078, content = "x", sig = "s")
        coEvery { nostrClient.fetchEvents(groupId, 0, myPub) } returns listOf(event)
        coEvery { eventDao.getEventIds(groupId) } returns emptyList()
        coEvery { eventProcessor.process(any(), any(), any(), any(), any(), any()) } returns
            EventProcessor.ProcessResult(stored = false)

        engine.pullEvents(groupId, 0, groupKey, lenientTimestamp = true)

        coVerify {
            eventProcessor.process(event, groupId, null, false, true, IngestionContext.RECONCILIATION)
        }
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
    fun `flushOutbox does not evict critical group_meta after repeated failures`() = runBlocking {
        val pending =
            listOf(
                OutboxEntity(
                    "e1",
                    """{"id":"e1","tags":[["t","group_meta"]]}""",
                    100,
                    retryCount = 999,
                    eventType = "group_meta"
                )
            )
        coEvery { outboxDao.getAll() } returns pending
        coEvery { nostrClient.publishJson(any()) } returns false

        val count = engine.flushOutbox()
        assertEquals(0, count)
        coVerify { outboxDao.incrementRetry("e1", any()) }
        coVerify(exactly = 0) { outboxDao.delete("e1") }
    }

    @Test
    fun `flushOutbox never evicts a non-critical row however often it failed`() = runBlocking {
        val longAgo = System.currentTimeMillis() / 1000 - 30 * 86400
        val pending = listOf(stuckRow("e1", lastRetryAt = longAgo, eventType = "expense"))
        coEvery { outboxDao.getAll() } returns pending
        coEvery { nostrClient.publishJson(any()) } returns false

        engine.flushOutbox()

        coVerify(exactly = 0) { outboxDao.delete(any()) }
        coVerify { outboxDao.incrementRetry("e1", any()) }
    }

    @Test
    fun `flushOutbox backs off a stuck row that was attempted recently`() = runBlocking {
        val now = System.currentTimeMillis() / 1000
        val pending = listOf(stuckRow("e1", lastRetryAt = now - 3600, eventType = "expense"))
        coEvery { outboxDao.getAll() } returns pending
        coEvery { nostrClient.publishJson(any()) } returns true

        val count = engine.flushOutbox()

        assertEquals(0, count)
        coVerify(exactly = 0) { nostrClient.publishJson(any()) }
        coVerify(exactly = 0) { outboxDao.delete(any()) }
        coVerify(exactly = 0) { outboxDao.incrementRetry(any(), any()) }
    }

    @Test
    fun `flushOutbox attempts a stuck row once its back-off window has passed`() = runBlocking {
        val now = System.currentTimeMillis() / 1000
        val pending =
            listOf(stuckRow("e1", lastRetryAt = now - SyncEngine.STUCK_RETRY_INTERVAL_SECS - 1, eventType = "expense"))
        coEvery { outboxDao.getAll() } returns pending
        coEvery { nostrClient.publishJson(any()) } returns true

        val count = engine.flushOutbox()

        assertEquals(1, count)
        coVerify(exactly = 1) { nostrClient.publishJson(any()) }
        coVerify { outboxDao.delete("e1") }
    }

    @Test
    fun `flushOutbox attempts a stuck row that has never recorded a retry time`() = runBlocking {
        val pending = listOf(stuckRow("e1", lastRetryAt = null, eventType = "expense"))
        coEvery { outboxDao.getAll() } returns pending
        coEvery { nostrClient.publishJson(any()) } returns false

        engine.flushOutbox()

        coVerify(exactly = 1) { nostrClient.publishJson(any()) }
    }

    @Test
    fun `flushOutbox always attempts critical rows regardless of back-off`() = runBlocking {
        val now = System.currentTimeMillis() / 1000
        val pending =
            listOf(
                stuckRow("meta", lastRetryAt = now - 60, eventType = "group_meta"),
                stuckRow("rot", lastRetryAt = now - 60, eventType = "key_rotation"),
                stuckRow("rev", lastRetryAt = now - 60, eventType = "key_revocation"),
                stuckRow("exp", lastRetryAt = now - 60, eventType = "expense")
            )
        coEvery { outboxDao.getAll() } returns pending
        coEvery { nostrClient.publishJson(any()) } returns true

        val count = engine.flushOutbox()

        assertEquals(3, count)
        coVerify { outboxDao.delete("meta") }
        coVerify { outboxDao.delete("rot") }
        coVerify { outboxDao.delete("rev") }
        coVerify(exactly = 0) { outboxDao.delete("exp") }
    }

    @Test
    fun `flushOutbox still attempts rows below the stuck threshold`() = runBlocking {
        val now = System.currentTimeMillis() / 1000
        val pending =
            listOf(
                OutboxEntity(
                    "e1",
                    """{"id":"e1"}""",
                    100,
                    retryCount = SyncEngine.MAX_RETRIES - 1,
                    lastRetryAt = now - 1,
                    eventType = "expense"
                )
            )
        coEvery { outboxDao.getAll() } returns pending
        coEvery { nostrClient.publishJson(any()) } returns true

        assertEquals(1, engine.flushOutbox())
    }

    @Test
    fun `flushOutbox returns 0 when outbox is empty`() = runBlocking {
        coEvery { outboxDao.getAll() } returns emptyList()
        assertEquals(0, engine.flushOutbox())
    }

    private fun stuckRow(id: String, lastRetryAt: Long?, eventType: String) = OutboxEntity(
        id,
        """{"id":"$id"}""",
        100,
        retryCount = SyncEngine.MAX_RETRIES,
        lastRetryAt = lastRetryAt,
        eventType = eventType
    )

    @Test
    fun `empty relay pull still recovers durable pending work`() = runBlocking {
        coEvery { nostrClient.fetchEvents(groupId, 0, myPub) } returns emptyList()
        engine.pullEvents(groupId, 0, groupKey)
        coVerify(atLeast = 2) { eventProcessor.retryDeferred(groupId) }
    }
}
