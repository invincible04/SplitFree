package com.splitfree.domain.model.sync

import com.splitfree.domain.crypto.NostrEvent

/**
 * History returned by a bounded fetch. [complete] requires EOSE coverage from every requested
 * relay, including ones that could not connect. [completedRelays] records independent progress:
 * other relays' unavailable history must never be certified by a fast fallback's EOSE.
 */
data class FetchResult(
    val events: List<NostrEvent>,
    val complete: Boolean,
    val completedRelays: Set<String> = emptySet()
)

/** Outcome of one pull for a group; relay cursors advance independently, even when [complete] is false. */
data class PullResult(val stored: Int, val complete: Boolean)

/**
 * Outcome of one outbox flush. [failed] counts due rows attempted in this pass that no relay
 * accepted; rows skipped by back-off appear in neither count.
 */
data class FlushResult(val published: Int, val failed: Int)
