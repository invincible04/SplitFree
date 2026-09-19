package com.splitfree.sync.worker

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.nostr.relay.RelayConnectionManager
import com.splitfree.data.repository.GroupRepository
import com.splitfree.di.ApplicationScope
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.isAddressedTo
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.sync.event.EventProcessor
import com.splitfree.sync.event.ExpenseNotifier
import com.splitfree.sync.event.IngestOutcome
import com.splitfree.util.DebugLog as Log
import com.splitfree.util.ProcessHealthTracker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Live relay session held only while the app is visible: on `onStart` it connects, catches every
 * group up from its persisted cursor and subscribes live; on `onStop` it cancels and releases the
 * connection. Bound once to the process lifecycle from `SplitFreeApp`; no service, no notification.
 */
@Singleton
class LiveSync
@Inject
constructor(
    private val nostrClient: NostrClient,
    private val groupRepo: GroupRepository,
    private val identity: IdentityContract,
    private val eventProcessor: EventProcessor,
    private val relayConnectionManager: RelayConnectionManager,
    private val syncEngine: SyncEngine,
    @ApplicationContext private val context: Context,
    @ApplicationScope private val appScope: CoroutineScope
) {
    private var bound = false

    // Lifecycle callbacks all arrive on the main thread, so start()/stop() never race and this
    // needs neither locking nor atomics.
    private var session: Job? = null

    /** Attach to [lifecycle]; main thread only, and only once per process. */
    fun bind(lifecycle: Lifecycle) {
        check(!bound) { "LiveSync is already bound" }
        bound = true
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = start()

                override fun onStop(owner: LifecycleOwner) = stop()
            }
        )
    }

    private fun start() {
        if (session?.isActive == true) return
        ProcessHealthTracker.heartbeat(context, "live_start")
        session = appScope.launch { runWhileStarted() }
    }

    private fun stop() {
        session?.cancel()
        session = null
        ProcessHealthTracker.heartbeat(context, "live_stop")
    }

    private suspend fun runWhileStarted() {
        // runSession never returns normally: its children are endless collectors, so the body only
        // comes back here after a failure. A persistent one (bad key store, failing query) would
        // otherwise reopen every relay socket every 30s for the whole visible session, hence the
        // growing delay; a session that held for a while earns a fresh budget.
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                runSession()
            } catch (e: CancellationException) {
                // Only our own cancellation (onStop) ends the session. A TimeoutCancellationException or
                // a cancelled deferred from a dependency is a failure like any other.
                if (!currentCoroutineContext().isActive) throw e
                sessionFailed(e)
            } catch (e: Exception) {
                sessionFailed(e)
            }
            attempt = if (SystemClock.elapsedRealtime() - startedAt >= STABLE_SESSION_MS) 1 else attempt + 1
            delay(minOf(BACKOFF_STEP_MS * attempt, BACKOFF_CAP_MS))
        }
    }

    private fun sessionFailed(e: Exception) {
        Log.e(TAG, "Live session failed; scheduled sync remains available", e)
        ProcessHealthTracker.heartbeat(context, "live_failed", e.javaClass.simpleName)
        scheduleFallbackSync()
    }

    private suspend fun runSession() {
        var acquired = false
        try {
            identity.observeHasIdentity().first { it }
            val initialGroups = connectWithBackoff()
            acquired = true
            // Acquisition may time out offline; later relay generations recover the missing history.
            ProcessHealthTracker.heartbeat(context, "live_acquired")
            coroutineScope {
                // incomingEvents has no replay and NostrClient marks an id seen before emitting it, so an
                // event arriving between subscribe() and collect() would be lost for the whole session.
                // Nothing may be subscribed until the collector has attached.
                val ready = CompletableDeferred<Unit>()
                val myPubkey = identity.getPublicKeyHex()
                launch {
                    nostrClient.incomingEvents.onSubscription {
                        ready.complete(Unit)
                    }.collect { handleLive(it, myPubkey) }
                }
                ready.await()

                val initialGeneration = nostrClient.connectionGeneration.value
                launch { observeConnection() }
                flushOutboxQuietly()
                recoverHistory(initialGroups, myPubkey, initialGeneration)
            }
        } finally {
            if (acquired) releaseQuietly()
        }
    }

    /** Returns the group list read just before the successful connect, so a later change is detectable. */
    private suspend fun connectWithBackoff(): List<Group> {
        var attempt = 0
        while (true) {
            val groups = groupRepo.getAll()
            try {
                // Forced: the fast path would only take a reference on whatever happens to be up, which
                // after a lease-less connect() elsewhere may not be this group set at all. connect() is a
                // no-op for relays already present, so a correct set costs nothing.
                relayConnectionManager.ensureConnected(forceReconnect = true)
                return groups
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt++
                Log.w(TAG, "Connect attempt $attempt failed: ${e.message}")
                delay(minOf(BACKOFF_STEP_MS * attempt, BACKOFF_CAP_MS))
            }
        }
    }

    private suspend fun observeConnection() {
        nostrClient.connectionState.collect { up ->
            if (up) {
                ProcessHealthTracker.heartbeat(context, "live_connected")
            } else {
                // Relay owns socket backoff. Only an aggregate outage that outlasts it needs help.
                delay(RECONNECT_GRACE_MS)
                if (!nostrClient.isConnected) {
                    Log.w(TAG, "All relays down, reconnecting")
                    reconnectRetainingSession()
                }
            }
        }
    }

    private data class CatchUpRetry(val attempts: Int, val retryAt: Long?)

    private suspend fun recoverHistory(initialGroups: List<Group>, myPubkey: String, initialGeneration: Long): Unit =
        coroutineScope {
            // The signal is only a wake-up: authoritative snapshots below retain changes during a pull.
            val changes = Channel<Unit>(Channel.CONFLATED)
            launch { groupRepo.observeAll().collect { changes.trySend(Unit) } }
            launch { nostrClient.connectionGeneration.collect { changes.trySend(Unit) } }
            var connectedRelays = relayConnectionManager.primaryRelaysOf(initialGroups).toSet()
            var subscribed = emptySet<String>()
            var processedGeneration = initialGeneration
            var generationNotBefore = 0L
            var initial = true
            var fallbackScheduled = false
            var reconcileAt = 0L
            val retries = mutableMapOf<String, CatchUpRetry>()

            while (currentCoroutineContext().isActive) {
                val groups = if (initial) initialGroups else groupRepo.getAll()
                val current = groups.mapTo(HashSet()) { it.id }
                for (gone in subscribed - current) nostrClient.unsubscribe(gone)
                retries.keys.retainAll(current)
                val fresh = current - subscribed
                val relays = relayConnectionManager.primaryRelaysOf(groups).toSet()
                val relaysChanged = relays != connectedRelays
                if (relaysChanged) {
                    Log.i(TAG, "Relay set changed, reconnecting")
                    reconnectRetainingSession()
                    connectedRelays = relays
                }
                if (!initial && fresh.isNotEmpty()) flushOutboxQuietly()

                val now = SystemClock.elapsedRealtime()
                val generation = nostrClient.connectionGeneration.value
                val generationChanged = generation != processedGeneration && now >= generationNotBefore
                val periodic = now >= reconcileAt
                val catchUpAll = initial || relaysChanged || generationChanged || periodic
                // Mark only the generation observed BEFORE pulling, never one that arrived in flight.
                if (catchUpAll) processedGeneration = generation
                for (group in groups) {
                    val retry = retries[group.id]
                    val timerDue = retry?.retryAt?.let { it <= SystemClock.elapsedRealtime() } == true
                    if (!catchUpAll && group.id !in fresh && !timerDue) continue
                    if (catchUpAndSubscribe(group.id, myPubkey)) {
                        retries.remove(group.id)
                    } else if (retry == null || timerDue) {
                        // An early generation retry must not replenish an incomplete group's timer budget.
                        val attempts = if (retry == null) 0 else retry.attempts + 1
                        val retryDelay = CATCH_UP_RETRY_DELAYS_MS.getOrNull(attempts)
                        val finishedAt = SystemClock.elapsedRealtime()
                        retries[group.id] = CatchUpRetry(attempts, retryDelay?.let { finishedAt + it })
                        if (retryDelay == null && !fallbackScheduled) {
                            fallbackScheduled = true
                            generationNotBefore = maxOf(generationNotBefore, finishedAt + BACKOFF_CAP_MS)
                            scheduleFallbackSync()
                        }
                    }
                }
                if (generationChanged) {
                    val cooldown = if (retries.values.any { it.retryAt == null }) BACKOFF_CAP_MS else BACKOFF_STEP_MS
                    generationNotBefore = SystemClock.elapsedRealtime() + cooldown
                }
                if (catchUpAll) reconcileAt = SystemClock.elapsedRealtime() + RECONCILE_INTERVAL_MS
                subscribed = current
                initial = false

                val generationAt = generationNotBefore.takeIf {
                    nostrClient.connectionGeneration.value != processedGeneration
                }
                val retryAt = retries.values.mapNotNull { it.retryAt }.minOrNull()
                val wakeAt = listOfNotNull(generationAt, retryAt, reconcileAt).minOrNull()
                if (wakeAt == null) {
                    changes.receive()
                } else {
                    val waitMs = wakeAt - SystemClock.elapsedRealtime()
                    if (waitMs > 0) withTimeoutOrNull(waitMs) { changes.receive() }
                }
            }
        }

    /** Returns whether the group's history is now complete; a group without a key has nothing to pull. */
    private suspend fun catchUpAndSubscribe(groupId: String, myPubkey: String): Boolean {
        val groupKey = groupRepo.getGroupKey(groupId)
        var complete = true
        if (groupKey != null) {
            val cursor = groupRepo.getGroupEntity(groupId)?.lastSyncTimestamp ?: 0L
            val since = if (cursor > 0) cursor - CURSOR_OVERLAP_SECS else 0L
            try {
                val pull = syncEngine.pullEvents(groupId, since, groupKey, notifyContext = context)
                if (pull.stored > 0) Log.i(TAG, "Caught up ${pull.stored} events for group $groupId")
                if (!pull.complete) Log.w(TAG, "Incomplete relay coverage for group $groupId")
                complete = pull.complete
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Completed relays retain their progress; uncovered history stays eligible for retry.
                Log.w(TAG, "Catch-up failed for group $groupId: ${e.message}")
                complete = false
            }
        }
        // Live filters have no authored-time lower bound; bounded periodic sweeps also repair gaps.
        nostrClient.subscribe(groupId, 0, myPubkey)
        return complete
    }

    private suspend fun handleLive(event: NostrEvent, myPubkey: String) {
        // A `p`-tagged event is addressed to the members it names; another member's copy of a
        // key_rotation cannot be decrypted here and would only fail the pipeline.
        if (!event.isAddressedTo(myPubkey)) return
        try {
            // onStop cancels this collector mid-event; the row must still reach APPLIED or FAILED, or the
            // next start re-processes it as pending.
            val result = eventProcessor.process(rawEvent = event, nonCancellable = true, lenientTimestamp = true)
            if (result.stored && result.outcome == IngestOutcome.APPLIED) {
                ExpenseNotifier.notifyIfNeeded(
                    context,
                    result.eventType!!,
                    result.decrypted,
                    result.authorHex!!,
                    myPubkey,
                    result.groupName ?: "Group"
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to process live event ${event.id.take(8)}: ${e.message}")
        }
    }

    private suspend fun reconnectRetainingSession() {
        relayConnectionManager.ensureConnected(forceReconnect = true)
        // ensureConnected hands out a reference on every success, including a forced reconnect; the
        // session already holds its own, so this one goes straight back.
        nostrClient.releaseConnection()
    }

    private suspend fun flushOutboxQuietly() {
        try {
            val flush = syncEngine.flushOutbox()
            if (flush.failed > 0) Log.w(TAG, "${flush.failed} outbox row(s) failed to publish")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Outbox flush failed: ${e.message}")
        }
    }

    private fun releaseQuietly() {
        try {
            nostrClient.releaseConnection()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release live connection: ${e.message}")
        }
    }

    private fun scheduleFallbackSync() {
        try {
            SyncScheduler.scheduleImmediateSync(context)
        } catch (e: Exception) {
            Log.w(TAG, "Could not schedule fallback sync: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "LiveSync"
        private const val CURSOR_OVERLAP_SECS = 3600L
        internal const val RECONCILE_INTERVAL_MS = 5 * 60_000L
        private const val BACKOFF_STEP_MS = 30_000L
        private const val BACKOFF_CAP_MS = 120_000L
        private const val RECONNECT_GRACE_MS = 5_000L
        private val CATCH_UP_RETRY_DELAYS_MS = listOf(30_000L, 60_000L, 120_000L)

        /** A session that lived this long before failing resets the restart back-off. */
        private const val STABLE_SESSION_MS = 60_000L
    }
}
