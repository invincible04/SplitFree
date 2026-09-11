package com.splitfree.data.nostr

import com.splitfree.data.local.dao.OutboxDao
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.util.DebugLog as Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Rate-limits event publishing to ~1 event/sec (500ms base + 100–900ms jitter) to avoid relay rate limits.
 * Events are queued and published sequentially; queue is capped at [MAX_QUEUE_SIZE].
 *
 * Every event handed here was already committed to the outbox, so a successful publish removes
 * its outbox row; otherwise the next `flushOutbox` would publish the same event a second time.
 */
@Singleton
class EventThrottler
@Inject
constructor(private val nostrClient: NostrClient, private val outboxDao: OutboxDao) {
    private val queue = ConcurrentLinkedQueue<NostrEvent>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val publishing = AtomicBoolean(false)
    private val intervalMs = 500L
    private val queueSize =
        java.util.concurrent.atomic
            .AtomicInteger(0)

    fun enqueue(event: NostrEvent) {
        val size = queueSize.incrementAndGet()
        if (size > MAX_QUEUE_SIZE) {
            queueSize.decrementAndGet()
            Log.w(TAG, "Queue full, dropping event ${event.id.take(8)}")
            return
        }
        queue.offer(event)
        Log.d(TAG, "Enqueued event ${event.id.take(8)}, queue size=$size")
        processIfNeeded()
    }

    private fun processIfNeeded() {
        if (!publishing.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (queue.isNotEmpty()) {
                    val event = queue.poll() ?: break
                    queueSize.decrementAndGet()
                    publishAndSettle(event)
                    delay(intervalMs + Random.nextLong(100, 900))
                }
            } finally {
                publishing.set(false)
                if (queue.isNotEmpty()) processIfNeeded()
            }
        }
    }

    /**
     * Publish one event and, on success, remove it from the outbox. A failed publish leaves the
     * row for `SyncEngine.flushOutbox` to retry. A failure here must not kill the drain loop.
     */
    private suspend fun publishAndSettle(event: NostrEvent) {
        try {
            if (nostrClient.publish(event)) outboxDao.delete(event.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "publish ${event.id.take(8)} failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "EventThrottler"
        private const val MAX_QUEUE_SIZE = 500
    }
}
