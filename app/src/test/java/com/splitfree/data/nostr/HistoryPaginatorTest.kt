package com.splitfree.data.nostr

import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.sync.HistoryRange
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryPaginatorTest {
    private fun event(index: Int, timestamp: Long) = NostrEvent(
        id = index.toString(16).padStart(64, '0'),
        pubkey = "author",
        createdAt = timestamp,
        kind = 30078,
        content = "event-$index"
    )

    @Test
    fun `deadline preserves completed partitions and resumes the unfinished page`() = runTest {
        val ranges = listOf(HistoryRange(1, 1), HistoryRange(2, 2))
        val first = event(1, 1)
        val result = HistoryPaginator.fetch(ranges) { range ->
            if (range == ranges.first()) {
                delay(12_000)
                HistoryPaginator.Page(listOf(first), true)
            } else {
                awaitCancellation()
            }
        }
        assertEquals(HistoryPaginator.DEADLINE_MS, currentTime)
        assertEquals(listOf(first), result.events)
        assertEquals(listOf(ranges.last()), result.pending)
        val resumed = HistoryPaginator.fetch(result.pending) { HistoryPaginator.Page(listOf(event(2, 2)), true) }
        assertTrue(resumed.pending.isEmpty())
    }

    @Test
    fun `byte saturated trustworthy page subdivides even below record limit`() = runTest {
        val ranges = mutableListOf<HistoryRange>()
        val result = HistoryPaginator.fetch(listOf(HistoryRange(1, 2))) { range ->
            ranges += range
            when {
                range.since != range.until -> HistoryPaginator.Page(listOf(event(2, 2)), true, saturated = true)
                else -> HistoryPaginator.Page(listOf(event(range.since.toInt(), range.since)), true)
            }
        }
        assertEquals(listOf(HistoryRange(1, 2), HistoryRange(1, 1), HistoryRange(2, 2)), ranges)
        assertEquals(listOf(1L, 2L), result.events.map { it.createdAt })
        assertTrue(result.pending.isEmpty())
    }

    @Test
    fun `saturation without EOSE cannot subdivide away incomplete history`() = runTest {
        val range = HistoryRange(1, 2)
        val result = HistoryPaginator.fetch(listOf(range)) {
            HistoryPaginator.Page(listOf(event(2, 2)), false, saturated = true)
        }
        assertEquals(listOf(range), result.pending)
    }

    @Test
    fun `page budget bounds pathological full timestamp buckets and leaves resumable frontier`() = runTest {
        var calls = 0
        val records = (0 until HistoryPaginator.PAGE_SIZE).map { event(it, 1) }
        val result = HistoryPaginator.fetch(listOf(HistoryRange(1, 1))) {
            calls++
            HistoryPaginator.Page(records, true)
        }
        assertEquals(HistoryPaginator.MAX_PAGES, calls)
        assertFalse(result.pending.isEmpty())
        assertTrue(result.pending.size <= HistoryPaginator.MAX_FRONTIER)
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun `fully specified saturated id remains incomplete rather than silently skipped`() = runTest {
        val range = HistoryRange(1, 1, "a".repeat(64))
        val result = HistoryPaginator.fetch(listOf(range)) {
            HistoryPaginator.Page(emptyList(), true, saturated = true)
        }
        assertEquals(listOf(range), result.pending)
    }
}
