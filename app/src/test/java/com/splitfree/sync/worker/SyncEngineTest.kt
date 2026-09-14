package com.splitfree.sync.worker

import com.splitfree.data.identity.IdentityManager
import com.splitfree.data.local.dao.EventDao
import com.splitfree.data.local.dao.OutboxDao
import com.splitfree.data.local.entities.OutboxEntity
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.sync.FetchResult
import com.splitfree.domain.model.sync.FlushResult
import com.splitfree.domain.model.sync.PullResult
import com.splitfree.sync.event.EventProcessor
import com.splitfree.sync.event.IngestionContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        coEvery { nostrClient.fetchEvents(groupId, 0, myPub) } returns FetchResult(listOf(event), complete = true)
        coEvery { eventDao.getEventIds(groupId) } returns listOf("e1") // already known

        val result = engine.pullEvents(groupId, 0, groupKey)
        assertEquals(PullResult(stored = 0, complete = true), result)
        coVerify(exactly = 0) { eventProcessor.process(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `pullEvents processes new events and returns count`() = runBlocking {
        val event = NostrEvent(id = "e1", pubkey = myPub, createdAt = 100, kind = 30078, content = "x", sig = "s")
        coEvery { nostrClient.fetchEvents(groupId, 0, myPub) } returns FetchResult(listOf(event), complete = true)
        coEvery { eventDao.getEventIds(groupId) } returns emptyList()
        coEvery { eventProcessor.process(any(), any(), any(), any(), any(), any()) } returns
            com.splitfree.sync.event.EventProcessor.ProcessResult(
                stored = true,
                eventType = "expense",
                authorHex = myPub,
                groupName = "Test"
            )

        val result = engine.pullEvents(groupId, 0, groupKey)
        assertEquals(PullResult(stored = 1, complete = true), result)
        coVerify { groupRepo.updateLastSync(groupId, any()) }
    }

    @Test
    fun `pullEvents leaves another group's gift wraps to that group's pull`() = runBlocking {
        // The recipient filter returns this member's envelopes for every group; the pull must neither
        // ingest them under its own group nor count them as failures.
        val forOther = NostrEvent(
            id = "w1",
            pubkey = "0".repeat(64),
            createdAt = 100,
            kind = 1059,
            tags = listOf(listOf("p", myPub), listOf("g", "other-group")),
            content = "x",
            sig = "s"
        )
        val forThis = NostrEvent(
            id = "w2",
            pubkey = "0".repeat(64),
            createdAt = 100,
            kind = 1059,
            tags = listOf(listOf("p", myPub), listOf("g", groupId)),
            content = "x",
            sig = "s"
        )
        val untagged = NostrEvent(
            id = "w3",
            pubkey = "0".repeat(64),
            createdAt = 100,
            kind = 1059,
            tags = listOf(listOf("p", myPub)),
            content = "x",
            sig = "s"
        )
        coEvery { nostrClient.fetchEvents(groupId, 0, myPub) } returns
            FetchResult(listOf(forOther, forThis, untagged), complete = true)
        coEvery { eventDao.getEventIds(groupId) } returns emptyList()
        coEvery { eventProcessor.process(any(), any(), any(), any(), any(), any()) } returns
            EventProcessor.ProcessResult(stored = true, eventType = "expense", authorHex = myPub, groupName = "Test")

        engine.pullEvents(groupId, 0, groupKey)

        coVerify(exactly = 0) { eventProcessor.process(forOther, any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { eventProcessor.process(forThis, groupId, any(), any(), any(), any()) }
        // Without an outer tag the inner, signed tag decides inside the processor.
        coVerify(exactly = 1) { eventProcessor.process(untagged, groupId, any(), any(), any(), any()) }
    }

    @Test
    fun `pullEvents advances the cursor after a complete fetch even with nothing new`() = runBlocking {
        coEvery { nostrClient.fetchEvents(groupId, 0, myPub) } returns FetchResult(emptyList(), complete = true)
        coEvery { eventDao.getEventIds(groupId) } returns emptyList()

        val result = engine.pullEvents(groupId, 0, groupKey)

        assertEquals(PullResult(stored = 0, complete = true), result)
        coVerify(exactly = 1) { groupRepo.updateLastSync(groupId, any()) }
    }

    @Test
    fun `pullEvents leaves the cursor alone after an incomplete fetch even when events were stored`() = runBlocking {
        val event = NostrEvent(id = "e1", pubkey = myPub, createdAt = 100, kind = 30078, content = "x", sig = "s")
        coEvery { nostrClient.fetchEvents(groupId, 0, myPub) } returns FetchResult(listOf(event), complete = false)
        coEvery { eventDao.getEventIds(groupId) } returns emptyList()
        coEvery { eventProcessor.process(any(), any(), any(), any(), any(), any()) } returns
            EventProcessor.ProcessResult(stored = true, eventType = "expense", authorHex = myPub, groupName = "Test")

        val result = engine.pullEvents(groupId, 0, groupKey)

        assertEquals(PullResult(stored = 1, complete = false), result)
        coVerify(exactly = 0) { groupRepo.updateLastSync(any(), any()) }
    }

    @Test
    fun `pullEvents writes the time the fetch began not the time processing ended`() = runBlocking {
        val event = NostrEvent(id = "e1", pubkey = myPub, createdAt = 100, kind = 30078, content = "x", sig = "s")
        var fetchEnteredAt = 0L
        coEvery { nostrClient.fetchEvents(groupId, 0, myPub) } coAnswers {
            // Hold the fetch until the wall-clock second rolls over (at most 1s): a "now after processing"
            // cursor is then >= fetchEnteredAt + 1 while the fetch-start cursor is <= fetchEnteredAt.
            fetchEnteredAt = System.currentTimeMillis() / 1000
            while (System.currentTimeMillis() / 1000 == fetchEnteredAt) Thread.sleep(5)
            FetchResult(listOf(event), complete = true)
        }
        coEvery { eventDao.getEventIds(groupId) } returns emptyList()
        coEvery { eventProcessor.process(any(), any(), any(), any(), any(), any()) } returns
            EventProcessor.ProcessResult(stored = true, eventType = "expense", authorHex = myPub, groupName = "Test")
        val cursor = slot<Long>()
        coEvery { groupRepo.updateLastSync(groupId, capture(cursor)) } returns Unit

        engine.pullEvents(groupId, 0, groupKey)

        assertTrue(
            "cursor ${cursor.captured} must not be later than fetch entry $fetchEnteredAt",
            cursor.captured <= fetchEnteredAt
        )
    }

    @Test
    fun `pullEvents processes a newest-first relay batch oldest-first`() = runBlocking {
        // Relays hand back newest-first; key_rotation must land in epoch order and group_meta is LWW.
        val newer = NostrEvent(id = "e2", pubkey = myPub, createdAt = 300, kind = 30078, content = "x", sig = "s")
        val older = NostrEvent(id = "e1", pubkey = myPub, createdAt = 100, kind = 30078, content = "x", sig = "s")
        coEvery { nostrClient.fetchEvents(groupId, 0, myPub) } returns
            FetchResult(listOf(newer, older), complete = true)
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
        coEvery { nostrClient.fetchEvents(groupId, 0, myPub) } returns FetchResult(listOf(event), complete = true)
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
        coEvery { nostrClient.fetchEvents(groupId, 0, myPub) } returns FetchResult(listOf(event), complete = true)
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

        val result = engine.flushOutbox()
        assertEquals(FlushResult(published = 1, failed = 0), result)
        coVerify { outboxDao.delete("e1") }
    }

    @Test
    fun `flushOutbox increments retry on failure`() = runBlocking {
        val pending = listOf(OutboxEntity("e1", """{"id":"e1"}""", 100))
        coEvery { outboxDao.getAll() } returns pending
        coEvery { nostrClient.publishJson(any()) } returns false

        val result = engine.flushOutbox()
        assertEquals(FlushResult(published = 0, failed = 1), result)
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

        val result = engine.flushOutbox()
        assertEquals(FlushResult(published = 0, failed = 1), result)
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

        val result = engine.flushOutbox()

        assertEquals(FlushResult(published = 0, failed = 0), result)
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

        val result = engine.flushOutbox()

        assertEquals(FlushResult(published = 1, failed = 0), result)
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

        val result = engine.flushOutbox()

        assertEquals(FlushResult(published = 3, failed = 0), result)
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

        assertEquals(FlushResult(published = 1, failed = 0), engine.flushOutbox())
    }

    @Test
    fun `flushOutbox reports nothing when outbox is empty`() = runBlocking {
        coEvery { outboxDao.getAll() } returns emptyList()
        assertEquals(FlushResult(published = 0, failed = 0), engine.flushOutbox())
    }

    @Test
    fun `flushOutbox counts published and failed rows of one pass separately`() = runBlocking {
        val pending = listOf(
            OutboxEntity("ok", """{"id":"ok"}""", 100),
            OutboxEntity("bad", """{"id":"bad"}""", 100),
            stuckRow("backed-off", lastRetryAt = System.currentTimeMillis() / 1000 - 60, eventType = "expense")
        )
        coEvery { outboxDao.getAll() } returns pending
        coEvery { nostrClient.publishJson("""{"id":"ok"}""") } returns true
        coEvery { nostrClient.publishJson("""{"id":"bad"}""") } returns false

        assertEquals(FlushResult(published = 1, failed = 1), engine.flushOutbox())
    }

    @Test
    fun `hasDueOutbox is false for an empty or fully backed-off outbox and true once a row is due`() = runBlocking {
        val now = System.currentTimeMillis() / 1000
        coEvery { outboxDao.getAll() } returns emptyList()
        assertFalse(engine.hasDueOutbox())

        coEvery { outboxDao.getAll() } returns listOf(stuckRow("e1", lastRetryAt = now - 60, eventType = "expense"))
        assertFalse(engine.hasDueOutbox())

        coEvery { outboxDao.getAll() } returns
            listOf(
                stuckRow("e1", lastRetryAt = now - 60, eventType = "expense"),
                OutboxEntity("e2", """{"id":"e2"}""", 100)
            )
        assertTrue(engine.hasDueOutbox())
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
        coEvery { nostrClient.fetchEvents(groupId, 0, myPub) } returns FetchResult(emptyList(), complete = true)
        engine.pullEvents(groupId, 0, groupKey)
        coVerify(atLeast = 2) { eventProcessor.retryDeferred(groupId) }
    }
}
