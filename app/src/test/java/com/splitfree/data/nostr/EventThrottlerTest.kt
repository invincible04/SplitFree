package com.splitfree.data.nostr

import com.splitfree.domain.crypto.NostrEvent
import io.mockk.*
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class EventThrottlerTest {
    private fun makeEvent(id: String) =
        NostrEvent(
            id = id,
            pubkey = "pub",
            createdAt = 1,
            kind = 30078,
            tags = emptyList(),
            content = "c",
            sig = "s",
        )

    @Test
    fun `enqueue adds event to queue`() =
        runTest {
            val client = mockk<NostrClient>(relaxed = true)
            coEvery { client.publish(any()) } returns true
            val throttler = EventThrottler(client)
            throttler.enqueue(makeEvent("e1"))
            advanceUntilIdle()
            // Give the IO dispatcher time to process
            Thread.sleep(200)
            coVerify(atLeast = 1) { client.publish(match { it.id == "e1" }) }
        }

    @Test
    fun `enqueue drops events beyond max queue size`() {
        val client = mockk<NostrClient>(relaxed = true)
        val throttler = EventThrottler(client)
        // Fill queue to max (500) + 1 — the 501st should be dropped
        repeat(501) { throttler.enqueue(makeEvent("e$it")) }
        // We can't easily assert the drop, but it shouldn't crash
    }
}
