package com.splitfree.data.nostr

import kotlinx.coroutines.*
import rust.nostr.sdk.Event
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Throttles event publishing to 2 events/sec to avoid relay rate limits.
 * Design doc Section 5.7.
 */
@Singleton
class EventThrottler @Inject constructor(
    private val nostrClient: NostrClient
) {
    private val queue = ConcurrentLinkedQueue<Event>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val publishing = AtomicBoolean(false)
    private val intervalMs = 500L

    fun enqueue(event: Event) {
        queue.offer(event)
        processIfNeeded()
    }

    private fun processIfNeeded() {
        if (!publishing.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (queue.isNotEmpty()) {
                    val event = queue.poll() ?: break
                    nostrClient.publish(event)
                    delay(intervalMs)
                }
            } finally {
                publishing.set(false)
                if (queue.isNotEmpty()) processIfNeeded()
            }
        }
    }
}
