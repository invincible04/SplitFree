package com.splitfree.domain.model.sync

import com.splitfree.domain.crypto.NostrEvent

/**
 * Outcome of one history fetch. [complete] is relative to the relays that were connected when the
 * request was sent and stayed connected until they answered EOSE; it says nothing about relays that
 * were unreachable at the time. When false, [events] may be missing history.
 */
data class FetchResult(val events: List<NostrEvent>, val complete: Boolean)

/** Outcome of one pull for a group; the group's cursor advances only when [complete]. */
data class PullResult(val stored: Int, val complete: Boolean)

/**
 * Outcome of one outbox flush. [failed] counts due rows attempted in this pass that no relay
 * accepted; rows skipped by back-off appear in neither count.
 */
data class FlushResult(val published: Int, val failed: Int)
