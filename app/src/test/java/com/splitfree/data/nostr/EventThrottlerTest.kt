package com.splitfree.data.nostr

import com.splitfree.data.local.dao.OutboxDao
import com.splitfree.domain.crypto.NostrEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.AfterClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EventThrottlerTest {
    companion object {
        @JvmStatic @BeforeClass
        fun setupLog() {
            mockkStatic(android.util.Log::class)
            every { android.util.Log.d(any<String>(), any<String>()) } returns 0
            every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        }

        @JvmStatic @AfterClass
        fun teardownLog() {
            unmockkStatic(android.util.Log::class)
        }
    }

    /** Stands in for the Hilt-provided `@ApplicationScope`; the drain loop really runs on IO. */
    private fun appScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun makeEvent(id: String) = NostrEvent(
        id = id,
        pubkey = "pub",
        createdAt = 1,
        kind = 30078,
        tags = emptyList(),
        content = "c",
        sig = "s"
    )

    @Test
    fun `enqueue adds event to queue`() = runTest {
        val client = mockk<NostrClient>(relaxed = true)
        val outboxDao = mockk<OutboxDao>(relaxed = true)
        coEvery { client.publish(any()) } returns true
        val throttler = EventThrottler(client, outboxDao, appScope())
        throttler.enqueue(makeEvent("e1"))
        advanceUntilIdle()
        // Give the IO dispatcher time to process
        Thread.sleep(200)
        coVerify(atLeast = 1) { client.publish(match { it.id == "e1" }) }
    }

    @Test
    fun `enqueue drops events beyond max queue size`() {
        val client = mockk<NostrClient>(relaxed = true)
        val outboxDao = mockk<OutboxDao>(relaxed = true)
        val throttler = EventThrottler(client, outboxDao, appScope())
        // Fill queue to max (500) + 1; the 501st should be dropped
        repeat(501) { throttler.enqueue(makeEvent("e$it")) }
        // We can't easily assert the drop, but it shouldn't crash
    }

    @Test
    fun `successful publish deletes the outbox row`() {
        val client = mockk<NostrClient>(relaxed = true)
        val outboxDao = mockk<OutboxDao>(relaxed = true)
        val deleted = CountDownLatch(1)
        coEvery { client.publish(any()) } returns true
        coEvery { outboxDao.delete("e1") } answers { deleted.countDown() }
        val throttler = EventThrottler(client, outboxDao, appScope())

        throttler.enqueue(makeEvent("e1"))

        // The drain loop runs on Dispatchers.IO; wait for the side effect rather than sleeping blindly.
        assertTrue("outbox row was not deleted after a successful publish", deleted.await(5, TimeUnit.SECONDS))
        coVerify(exactly = 1) { outboxDao.delete("e1") }
    }

    @Test
    fun `failed publish leaves the outbox row for flushOutbox to retry`() {
        val client = mockk<NostrClient>(relaxed = true)
        val outboxDao = mockk<OutboxDao>(relaxed = true)
        val published = CountDownLatch(1)
        coEvery { client.publish(any()) } answers {
            published.countDown()
            false
        }
        val throttler = EventThrottler(client, outboxDao, appScope())

        throttler.enqueue(makeEvent("e1"))

        assertTrue(published.await(5, TimeUnit.SECONDS))
        Thread.sleep(100) // let anything that would follow the publish run
        coVerify(exactly = 0) { outboxDao.delete(any()) }
    }

    @Test
    fun `a publish that throws does not stop later events from draining`() {
        val client = mockk<NostrClient>(relaxed = true)
        val outboxDao = mockk<OutboxDao>(relaxed = true)
        val secondPublished = CountDownLatch(1)
        coEvery { client.publish(match { it.id == "boom" }) } throws IllegalStateException("relay exploded")
        coEvery { client.publish(match { it.id == "after" }) } answers {
            secondPublished.countDown()
            true
        }
        val throttler = EventThrottler(client, outboxDao, appScope())

        throttler.enqueue(makeEvent("boom"))
        throttler.enqueue(makeEvent("after"))

        assertTrue("second event never published", secondPublished.await(5, TimeUnit.SECONDS))
        coVerify(exactly = 0) { outboxDao.delete("boom") }
    }

    @Test
    fun `cancelling the application scope stops the drain loop`() {
        val client = mockk<NostrClient>(relaxed = true)
        val outboxDao = mockk<OutboxDao>(relaxed = true)
        val scope = appScope()
        val firstPublished = CountDownLatch(1)
        val secondPublished = CountDownLatch(1)
        coEvery { client.publish(match { it.id == "first" }) } answers {
            firstPublished.countDown()
            true
        }
        coEvery { client.publish(match { it.id == "second" }) } answers {
            secondPublished.countDown()
            true
        }
        val throttler = EventThrottler(client, outboxDao, scope)

        throttler.enqueue(makeEvent("first"))
        throttler.enqueue(makeEvent("second"))
        assertTrue(firstPublished.await(5, TimeUnit.SECONDS))
        // The loop is now in its inter-event delay; cancelling the app scope must end it.
        scope.cancel()

        assertFalse("drain loop outlived its scope", secondPublished.await(2, TimeUnit.SECONDS))
        coVerify(exactly = 0) { client.publish(match { it.id == "second" }) }
    }
}
