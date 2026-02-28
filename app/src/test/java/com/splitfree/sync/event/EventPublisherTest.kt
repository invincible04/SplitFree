package com.splitfree.sync.event

import com.splitfree.data.local.dao.EventDao
import com.splitfree.data.local.dao.OutboxDao
import com.splitfree.data.local.entities.OutboxEntity
import com.splitfree.data.nostr.EventThrottler
import com.splitfree.domain.crypto.GiftWrapService
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EventPublisherTest {
    private val eventDao = mockk<EventDao>(relaxed = true)
    private val outboxDao = mockk<OutboxDao>(relaxed = true)
    private val throttler = mockk<EventThrottler>(relaxed = true)
    private val giftWrap = mockk<GiftWrapService>()
    private val groupRepo = mockk<GroupRepositoryContract>()
    private val identity = mockk<IdentityContract>()
    private lateinit var publisher: EventPublisher

    private val myPub = "aa".repeat(32)
    private val otherPub = "bb".repeat(32)
    private val event =
        NostrEvent(id = "evt1", pubkey = myPub, createdAt = 1000, kind = 30078, content = "enc", sig = "sig")

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        every { identity.getPublicKeyHex() } returns myPub
        coEvery { outboxDao.count() } returns 0
        coEvery { groupRepo.getById(any()) } returns null
        publisher = EventPublisher(eventDao, outboxDao, throttler, giftWrap, groupRepo, identity)
    }

    @Test
    fun `publishToGroup with gift wrap wraps for each member except self`() = runBlocking {
        every { giftWrap.enabled } returns true
        coEvery { groupRepo.getMembers("g1") } returns listOf(myPub, otherPub)
        every { giftWrap.wrapIfEnabled(any(), otherPub) } returns event.copy(id = "wrapped")

        publisher.publishToGroup(event, "g1", "enc", "expense")

        coVerify { eventDao.insert(match { it.eventId == "evt1" }) }
        verify { giftWrap.wrapIfEnabled(any(), otherPub) }
        verify(exactly = 0) { giftWrap.wrapIfEnabled(any(), myPub) }
    }

    @Test
    fun `publishToGroup without gift wrap publishes directly`() = runBlocking {
        every { giftWrap.enabled } returns false

        publisher.publishToGroup(event, "g1", "enc", "expense")

        coVerify { eventDao.insert(any()) }
        coVerify { outboxDao.insert(match { it.eventId == "evt1" }) }
        verify { throttler.enqueue(event) }
    }

    @Test
    fun `publishDirect always skips gift wrap`() = runBlocking {
        publisher.publishDirect(event, "g1", "enc", "expense")

        coVerify { eventDao.insert(any()) }
        coVerify { outboxDao.insert(any()) }
        verify { throttler.enqueue(event) }
    }

    @Test
    fun `saveAndQueue saves and queues but does not throttle`() = runBlocking {
        publisher.saveAndQueue(event, "g1", "enc", "snapshot")

        coVerify { eventDao.insert(any()) }
        coVerify { outboxDao.insert(any()) }
        verify(exactly = 0) { throttler.enqueue(any()) }
    }

    @Test
    fun `enqueueOutbox skips when outbox is full`() = runBlocking {
        coEvery { outboxDao.count() } returns 5000

        publisher.publishDirect(event, "g1", "enc", "expense")

        coVerify { eventDao.insert(any()) }
        coVerify(exactly = 0) { outboxDao.insert(any()) }
    }

    @Test
    fun `hasOutboxMatching returns true when predicate matches`() = runBlocking {
        coEvery { outboxDao.getAll() } returns listOf(
            OutboxEntity("e1", """{"type":"expense"}""", 1000)
        )
        assertTrue(publisher.hasOutboxMatching { "expense" in it })
        assertFalse(publisher.hasOutboxMatching { "settlement" in it })
    }
}
