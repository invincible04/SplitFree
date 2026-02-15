package com.splitfree.data.nostr

import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.util.DebugLog as Log
import kotlinx.coroutines.*
import java.security.SecureRandom
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Throttles event publishing to 2 events/sec to avoid relay rate limits.
 */
@Singleton
class EventThrottler
    @Inject
    constructor(
        private val nostrClient: NostrClient,
    ) {
        private val queue = ConcurrentLinkedQueue<NostrEvent>()
        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val publishing = AtomicBoolean(false)
        private val intervalMs = 500L
        private val secureRandom = SecureRandom()
        private val queueSize =
            java.util.concurrent.atomic
                .AtomicInteger(0)

        fun enqueue(event: NostrEvent) {
            if (queueSize.get() >= MAX_QUEUE_SIZE) {
                Log.w("EventThrottler", "Queue full, dropping event ${event.id.take(8)}")
                return
            }
            queue.offer(event)
            queueSize.incrementAndGet()
            Log.d("EventThrottler", "Enqueued event ${event.id.take(8)}, queue size=${queueSize.get()}")
            processIfNeeded()
        }

        private fun processIfNeeded() {
            if (!publishing.compareAndSet(false, true)) return
            scope.launch {
                try {
                    while (queue.isNotEmpty()) {
                        val event = queue.poll() ?: break
                        queueSize.decrementAndGet()
                        nostrClient.publish(event)
                        delay(intervalMs + (secureRandom.nextLong().ushr(1) % 800) + 100)
                    }
                } finally {
                    publishing.set(false)
                    if (queue.isNotEmpty()) processIfNeeded()
                }
            }
        }

        companion object {
            private const val MAX_QUEUE_SIZE = 500
        }
    }
