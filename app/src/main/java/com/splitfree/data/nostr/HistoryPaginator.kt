package com.splitfree.data.nostr

import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.sync.HistoryRange
import kotlinx.coroutines.withTimeoutOrNull

/** Disjoint partitions avoid timestamp-tie skips without relying on relay result ordering. */
internal object HistoryPaginator {
    const val PAGE_SIZE = 128
    const val MAX_PAGES = 16
    const val MAX_EVENTS = PAGE_SIZE * MAX_PAGES
    const val MAX_BYTES = 8 * 1024 * 1024
    const val MAX_FRONTIER = 1024
    const val DEADLINE_MS = 20_000L

    data class Page(val events: List<NostrEvent>, val complete: Boolean, val saturated: Boolean = false)
    data class Result(val events: List<NostrEvent>, val pending: List<HistoryRange>)

    suspend fun fetch(initial: List<HistoryRange>, page: suspend (HistoryRange) -> Page): Result {
        require(initial.size <= MAX_FRONTIER)
        val pending = ArrayDeque(initial)
        val events = linkedMapOf<String, NostrEvent>()
        var bytes = 0
        withTimeoutOrNull(DEADLINE_MS) {
            repeat(MAX_PAGES) {
                val range = pending.firstOrNull() ?: return@withTimeoutOrNull
                val result = page(range)
                if (result.complete && (result.saturated || result.events.size >= PAGE_SIZE)) {
                    val children = split(range) ?: return@withTimeoutOrNull
                    if (pending.size - 1 + children.size > MAX_FRONTIER) return@withTimeoutOrNull
                    pending.removeFirst()
                    children.asReversed().forEach { pending.addFirst(it) }
                } else {
                    val added = result.events.filter { it.id !in events }
                    val size = added.sumOf { it.toJson().length.toLong() * 2 }
                    if (events.size + added.size > MAX_EVENTS || bytes + size > MAX_BYTES) return@withTimeoutOrNull
                    added.forEach { events[it.id] = it }
                    bytes += size.toInt()
                    // Incomplete pages may carry useful evidence, but keep their entire partition outstanding.
                    if (!result.complete) return@withTimeoutOrNull
                    pending.removeFirst()
                }
            }
        }
        return Result(events.values.toList(), pending.toList())
    }

    private fun split(range: HistoryRange): List<HistoryRange>? = when {
        range.since < range.until -> {
            val middle = range.since + (range.until - range.since) / 2
            listOf(range.copy(until = middle), range.copy(since = middle + 1))
        }
        range.idPrefix.length < 64 -> "0123456789abcdef".map { range.copy(idPrefix = range.idPrefix + it) }
        else -> null
    }
}
