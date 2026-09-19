package com.splitfree.domain.model.sync

import com.splitfree.domain.crypto.NostrEvent

/**
 * History returned by a bounded fetch. [complete] requires EOSE coverage from every requested
 * relay, including ones that could not connect. [completedRelays] records independent progress:
 * other relays' unavailable history must never be certified by a fast fallback's EOSE.
 * This is relative to protocol responses: silent truncation and relay retention cannot be proven.
 * [saturatedRelays] have trustworthy EOSE but hit local resource bounds; callers must subdivide.
 */
data class FetchResult(
    val events: List<NostrEvent>,
    val complete: Boolean,
    val completedRelays: Set<String> = emptySet(),
    val pendingByRelay: Map<String, List<HistoryRange>> = emptyMap(),
    val saturatedRelays: Set<String> = emptySet()
)

/** Outcome of one pull for a group; relay cursors advance independently, even when [complete] is false. */
data class PullResult(val stored: Int, val complete: Boolean)

/**
 * Outcome of one outbox flush. [failed] counts due rows attempted in this pass that no relay
 * accepted; rows skipped by back-off appear in neither count.
 */
data class FlushResult(val published: Int, val failed: Int)

/** An inclusive authored-time partition, never a relay-arrival watermark. */
data class HistoryRange(val since: Long, val until: Long, val idPrefix: String = "")
