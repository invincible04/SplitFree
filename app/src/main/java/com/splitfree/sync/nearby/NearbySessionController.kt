package com.splitfree.sync.nearby

import com.splitfree.data.ble.BleEvent
import com.splitfree.di.ApplicationScope
import com.splitfree.util.DebugLog as Log
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Screen ownership token; commands take effect only while this token is current. */
class NearbyOwner internal constructor(internal val id: Long)

/**
 * Owns one foreground Nearby run and accepts intents from the current [NearbyOwner].
 *
 * - Public intents enqueue commands; one actor applies them in channel order.
 * - Concurrent callers have no additional ordering guarantee, and returning from an intent does not mean its state
 *   change has completed.
 * - [state] describes radio/attempt progress; the coordinator publishes protocol progress separately.
 * - Activation, cleanup and radio task waits run outside the actor.
 * - Run identity fences their queued outcomes.
 * - Start/request tasks outlive run cancellation so cleanup can wait for late SDK submissions before allowing a
 *   replacement run.
 * - Event collectors and timers are cancelled with the run; all work still depends on [appScope] remaining alive.
 * - [timeline] retains bounded diagnostics, not a durable audit log.
 */
@Singleton
class NearbySessionController(
    private val radio: NearbyRadio,
    private val coordinator: NearbySessionCoordinator,
    private val appScope: CoroutineScope,
    private val clock: () -> Long,
    private val random: () -> Double
) {
    @Inject
    constructor(
        radio: NearbyRadio,
        coordinator: NearbySessionCoordinator,
        @ApplicationScope appScope: CoroutineScope
    ) : this(radio, coordinator, appScope, System::currentTimeMillis, { Random.nextDouble() })

    private val _state = MutableStateFlow(NearbyRunState())
    val state: StateFlow<NearbyRunState> = _state.asStateFlow()

    private val _timeline = MutableStateFlow<List<TimelineEntry>>(emptyList())
    val timeline: StateFlow<List<TimelineEntry>> = _timeline.asStateFlow()

    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val ownerIds = AtomicLong()

    /** Foreground pause flag; attempt timeouts only consume time while this is false. */
    private val paused = MutableStateFlow(false)

    // Actor-owned state: only touched from commands executed by the actor coroutine.
    private var currentOwnerId: Long? = null
    private var run: Run? = null
    private var pendingGroupId: String? = null
    private var attemptIds = 0L

    /** Per-run bookkeeping that is not part of the observable state. */
    private inner class Run(val runId: Long, val groupId: String) {
        val scope = CoroutineScope(appScope.coroutineContext + SupervisorJob(appScope.coroutineContext[Job]))
        var activation: Job? = null
        val tasks = mutableListOf<Deferred<*>>()
        val attemptTimers = HashMap<String, Job>()
        val attempts = HashMap<String, NearbyConnectionAttempt>()
        var searchTimer: Job? = null
        var cleanupComplete = false

        /** Early submission gate, not an atomic SDK fence; cleanup also drains work that passed this check. */
        @Volatile
        var retired = false
    }

    private enum class Capability(val label: String) {
        ADVERTISING("advertising"),
        DISCOVERY("discovery")
    }

    init {
        appScope.launch {
            for (command in commands) {
                try {
                    command()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Command failed: ${e.message}")
                }
            }
        }
    }

    // ------------------------------------------------------------------ public API

    /** Enqueues ownership transfer and stop of the previous run; the returned token is not a cleanup barrier. */
    fun acquire(): NearbyOwner {
        val owner = NearbyOwner(ownerIds.incrementAndGet())
        post {
            endRun()
            currentOwnerId = owner.id
            paused.value = false
            record("owner_acquired")
        }
        return owner
    }

    /** Stops any run and clears the current owner; ignored for a non-current owner. */
    fun release(owner: NearbyOwner) {
        post {
            if (!isCurrentOwner(owner)) return@post
            endRun()
            currentOwnerId = null
            paused.value = false
            record("owner_released")
        }
    }

    /**
     * Enqueues a run for [groupId].
     *
     * - A same-group request is inert during startup; once active, it retries only capabilities in
     *   [CapabilityState.Failed] with a fresh retry budget.
     * - A different group requires cleanup first.
     * - While cleanup is outstanding, the latest requested group replaces any remembered start.
     */
    fun start(owner: NearbyOwner, groupId: String) {
        post {
            if (!isCurrentOwner(owner)) return@post
            val current = run
            when (_state.value.phase) {
                RunPhase.IDLE -> begin(groupId)
                RunPhase.FAILED -> if (current == null || current.cleanupComplete) begin(groupId) else remember(groupId)
                RunPhase.STARTING, RunPhase.ACTIVE ->
                    if (_state.value.groupId != groupId) {
                        stopRun()
                        remember(groupId)
                    } else if (current != null && _state.value.phase == RunPhase.ACTIVE) {
                        retryFailedCapabilities(current)
                    }

                RunPhase.STOPPING, RunPhase.WAITING_FOR_CLEANUP -> remember(groupId)
            }
        }
    }

    /** Enqueues stop and discards any remembered start; [RunPhase.IDLE] follows cleanup, including for a failed run. */
    fun stop(owner: NearbyOwner) {
        post {
            if (!isCurrentOwner(owner)) return@post
            endRun()
        }
    }

    /** Requests a connection to a discovered endpoint with no attempt in progress. */
    fun connect(owner: NearbyOwner, endpointId: String) {
        post {
            if (!isCurrentOwner(owner)) return@post
            val current = run
            val snapshot = _state.value
            val row = snapshot.peers[endpointId]
            val reason =
                when {
                    current == null || snapshot.phase != RunPhase.ACTIVE -> "not_active"
                    row == null -> "unknown_endpoint"
                    !row.discovered -> "not_discovered"
                    row.attempt !is AttemptState.None && row.attempt !is AttemptState.Failed -> "attempt_exists"
                    else -> null
                }
            if (current == null || row == null || reason != null) {
                record("connect_ignored", endpointId = endpointId, detail = reason)
                return@post
            }
            val attemptId = ++attemptIds
            val connectionAttempt = NearbyConnectionAttempt(endpointId)
            current.attempts[endpointId] = connectionAttempt
            putPeer(row.copy(attempt = AttemptState.Requesting(attemptId)))
            record("connect_requested", endpointId = endpointId, attemptId = attemptId)
            val task =
                appScope.async {
                    try {
                        if (current.retired) RadioOutcome.Cancelled else radio.requestConnection(connectionAttempt)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        RadioOutcome.Failure(RadioFailureKind.UNKNOWN, null, e.message ?: e.javaClass.simpleName)
                    }
                }
            track(current, task)
            appScope.launch {
                val outcome = task.await()
                post { onRequestOutcome(current, endpointId, attemptId, outcome) }
            }
            startAttemptTimer(current, endpointId, attemptId)
        }
    }

    /** Abandons an attempt that has not connected; the row is removed unless the endpoint is discovered. */
    fun cancelAttempt(owner: NearbyOwner, endpointId: String) {
        post {
            if (!isCurrentOwner(owner)) return@post
            val current = run ?: return@post
            if (_state.value.phase != RunPhase.ACTIVE) return@post
            val row = _state.value.peers[endpointId] ?: return@post
            val attemptId = outgoingAttemptId(row.attempt) ?: return@post
            record("connect_cancelled", endpointId = endpointId, attemptId = attemptId)
            cancelAttemptTimer(current, endpointId)
            cancelConnectionAttempt(current, endpointId)
            settleAttempt(row, AttemptState.None)
        }
    }

    /** Pauses or resumes attempt timeouts while the owning screen is not in the foreground. */
    fun setForegroundPaused(owner: NearbyOwner, paused: Boolean) {
        post {
            if (!isCurrentOwner(owner)) return@post
            this.paused.value = paused
        }
    }

    // ------------------------------------------------------------------ run lifecycle

    private fun begin(groupId: String) {
        val runId = _state.value.runId + 1
        val started = Run(runId, groupId)
        run = started
        pendingGroupId = null
        _state.value = NearbyRunState(runId = runId, phase = RunPhase.STARTING, groupId = groupId)
        record("run_start")
        val eventsReady = CompletableDeferred<Unit>(started.scope.coroutineContext[Job])
        started.scope.launch {
            radio.events.onSubscription { eventsReady.complete(Unit) }
                .collect { event -> post { onEvent(started, event) } }
        }
        started.scope.launch {
            radio.fault.collect { fault -> if (fault != null) post { onFault(started, fault) } }
        }
        started.activation =
            appScope.launch {
                val error =
                    try {
                        coordinator.activate(started.groupId)
                        // SharedFlow has no replay: startup must wait for this run's row collector too.
                        eventsReady.await()
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e.message ?: e.javaClass.simpleName
                    }
                post { onActivated(started, error) }
            }
    }

    private fun remember(groupId: String) {
        pendingGroupId = groupId
        update { copy(phase = RunPhase.WAITING_FOR_CLEANUP, searchingLong = false) }
    }

    /** Stops whatever run or remembered start exists, in the way [stop] does. */
    private fun endRun() {
        val current = run
        when (_state.value.phase) {
            RunPhase.STARTING, RunPhase.ACTIVE -> stopRun()
            RunPhase.WAITING_FOR_CLEANUP -> {
                pendingGroupId = null
                record("stop_requested")
                update { copy(phase = RunPhase.STOPPING) }
            }

            RunPhase.FAILED -> {
                record("stop_requested")
                if (current == null || current.cleanupComplete) {
                    _state.value = NearbyRunState(runId = _state.value.runId)
                } else {
                    update { copy(phase = RunPhase.STOPPING) }
                }
            }

            RunPhase.STOPPING, RunPhase.IDLE -> Unit
        }
    }

    private fun stopRun() {
        val current = run ?: return
        record("stop_requested")
        update { copy(phase = RunPhase.STOPPING, searchingLong = false) }
        current.retired = true
        current.scope.cancel()
        launchCleanup(current, touchRadios = true)
    }

    private fun failRun(current: Run, failure: RunFailure, touchRadios: Boolean) {
        record("run_failed")
        update {
            copy(
                phase = RunPhase.FAILED,
                failure = failure,
                peers = emptyMap(),
                searchingLong = false,
                advertising = if (failure is RunFailure.NoCapability) advertising else CapabilityState.Stopped,
                discovery = if (failure is RunFailure.NoCapability) discovery else CapabilityState.Stopped
            )
        }
        current.retired = true
        current.scope.cancel()
        launchCleanup(current, touchRadios)
    }

    /**
     * Requests discovery/advertising stop before giving coordinator cleanup [CLOSE_GRACE_MS] to attempt protocol
     * closes.
     *
     * - Endpoint stop follows even if those coroutine joins time out; SDK calls are best effort.
     * - Late start/request submissions must settle before a second stop pass and the next run.
     * - Activation and deactivation must also finish, otherwise an old cleanup could tear down the replacement
     *   coordinator.
     * - The grace period bounds only the initial joins, not the whole cleanup or a blocking SDK call.
     */
    private fun launchCleanup(current: Run, touchRadios: Boolean) {
        appScope.launch {
            if (touchRadios) {
                safely("stopDiscovery") { radio.stopDiscovery() }
                safely("stopAdvertising") { radio.stopAdvertising() }
            }
            current.activation?.cancel()
            val deactivation =
                appScope.launch {
                    try {
                        coordinator.deactivate()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Coordinator deactivate failed: ${e.message}")
                    }
                }
            val closed =
                withTimeoutOrNull(CLOSE_GRACE_MS) {
                    current.activation?.join()
                    deactivation.join()
                } != null
            if (!closed) post { record("coordinator_cleanup_late", runId = current.runId) }
            if (touchRadios) safely("stopAllEndpoints") { radio.stopAllEndpoints() }
            current.tasks.toList().forEach { it.join() }
            if (touchRadios) {
                safely("stopDiscovery") { radio.stopDiscovery() }
                safely("stopAdvertising") { radio.stopAdvertising() }
                safely("stopAllEndpoints") { radio.stopAllEndpoints() }
            }
            current.activation?.join()
            deactivation.join()
            post { onCleanupComplete(current) }
        }
    }

    private fun onCleanupComplete(current: Run) {
        if (run !== current) return
        current.cleanupComplete = true
        record("cleanup_complete", runId = current.runId)
        val next = pendingGroupId
        when {
            next != null -> begin(next)
            _state.value.phase == RunPhase.FAILED -> Unit
            else -> _state.value = NearbyRunState(runId = current.runId)
        }
    }

    private fun onActivated(current: Run, error: String?) {
        if (!isCurrent(current) || _state.value.phase != RunPhase.STARTING) {
            record("ignored_stale_run", runId = current.runId)
            return
        }
        if (error != null) {
            record("coordinator_failed", detail = error)
            failRun(current, RunFailure.Coordinator(error), touchRadios = false)
            return
        }
        record("coordinator_ready")
        submitCapability(current, Capability.ADVERTISING, attempt = 1)
        submitCapability(current, Capability.DISCOVERY, attempt = 1)
        update { copy(phase = RunPhase.ACTIVE) }
        refreshSearchTimer(current)
    }

    // ------------------------------------------------------------------ capabilities

    /** Resubmits every capability the platform refused, as a fresh first attempt with the full retry budget. */
    private fun retryFailedCapabilities(current: Run) {
        Capability.values().forEach { capability ->
            if (_state.value.capability(capability) is CapabilityState.Failed) {
                submitCapability(current, capability, attempt = 1)
            }
        }
    }

    private fun submitCapability(current: Run, capability: Capability, attempt: Int) {
        setCapability(capability, CapabilityState.Starting)
        record("${capability.label}_submitted")
        val task =
            appScope.async {
                try {
                    if (current.retired) {
                        RadioOutcome.Cancelled
                    } else {
                        when (capability) {
                            Capability.ADVERTISING -> radio.startAdvertising()
                            Capability.DISCOVERY -> radio.startDiscovery()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    RadioOutcome.Failure(RadioFailureKind.UNKNOWN, null, e.message ?: e.javaClass.simpleName)
                }
            }
        track(current, task)
        appScope.launch {
            val outcome = task.await()
            post { onCapabilityOutcome(current, capability, attempt, outcome) }
        }
    }

    private fun onCapabilityOutcome(current: Run, capability: Capability, attempt: Int, outcome: RadioOutcome) {
        if (!isCurrent(current)) {
            record(
                "ignored_stale_run",
                runId = current.runId,
                statusCode = (outcome as? RadioOutcome.Failure)?.statusCode
            )
            return
        }
        if (_state.value.capability(capability) !is CapabilityState.Starting) return
        val label = capability.label
        val failure = outcome as? RadioOutcome.Failure
        when {
            outcome is RadioOutcome.Success || failure?.kind == RadioFailureKind.ALREADY_ACTIVE -> {
                setCapability(capability, CapabilityState.Running)
                record("${label}_running")
            }

            failure != null && failure.kind in TERMINAL_KINDS -> {
                setCapability(capability, CapabilityState.Failed(failure))
                record("${label}_failed", statusCode = failure.statusCode)
            }

            else -> {
                val reported = failure ?: RadioOutcome.Failure(RadioFailureKind.UNKNOWN, null, "cancelled")
                if (attempt <= RETRY_DELAYS_MS.size) {
                    val retrying = CapabilityState.Retrying(attempt, reported)
                    setCapability(capability, retrying)
                    record("${label}_retry", statusCode = reported.statusCode)
                    val delayMs = RETRY_DELAYS_MS[attempt - 1] + (random() * RETRY_JITTER_MS).toLong()
                    current.scope.launch {
                        delay(delayMs)
                        post {
                            if (isCurrent(current) && _state.value.capability(capability) == retrying) {
                                submitCapability(current, capability, attempt + 1)
                            }
                        }
                    }
                } else {
                    setCapability(capability, CapabilityState.Failed(reported))
                    record("${label}_failed", statusCode = reported.statusCode)
                }
            }
        }
        val snapshot = _state.value
        if (snapshot.advertising is CapabilityState.Failed && snapshot.discovery is CapabilityState.Failed) {
            failRun(current, RunFailure.NoCapability, touchRadios = true)
            return
        }
        refreshSearchTimer(current)
    }

    // ------------------------------------------------------------------ transport events

    private fun onFault(current: Run, fault: TransportFault) {
        if (!isCurrent(current)) {
            record("ignored_stale_run", runId = current.runId)
            return
        }
        record("transport_fault", detail = fault.kind)
        failRun(current, RunFailure.Transport(fault), touchRadios = true)
    }

    private fun onEvent(current: Run, event: BleEvent) {
        if (!isCurrent(current)) {
            record("ignored_stale_run", runId = current.runId)
            return
        }
        val peers = _state.value.peers
        when (event) {
            is BleEvent.PeerFound -> {
                val endpointId = event.peer.endpointId
                val existing = peers[endpointId]
                val attempt = existing?.attempt ?: AttemptState.None
                putPeer(NearbyPeerState(endpointId, event.peer.name, discovered = true, attempt = attempt))
                record("peer_found", endpointId = endpointId)
            }

            is BleEvent.PeerLost -> {
                val row = peers[event.endpointId] ?: return
                record("peer_lost", endpointId = event.endpointId)
                if (row.attempt is AttemptState.None || row.attempt is AttemptState.Failed) {
                    removePeer(event.endpointId)
                } else {
                    putPeer(row.copy(discovered = false))
                }
            }

            is BleEvent.Connected -> {
                val row = peers[event.endpointId]
                val attemptId = row?.let { outgoingAttemptId(it.attempt) }
                cancelAttemptTimer(current, event.endpointId)
                current.attempts.remove(event.endpointId)
                val base = row ?: NearbyPeerState(event.endpointId, event.endpointName, discovered = false)
                putPeer(base.copy(attempt = AttemptState.Connected(attemptId, event.isIncoming)))
                record("connected", endpointId = event.endpointId, attemptId = attemptId)
            }

            is BleEvent.ConnectionFailed -> {
                val row = peers[event.endpointId]
                val attemptId = row?.let { outgoingAttemptId(it.attempt) }
                record(
                    "connection_failed",
                    endpointId = event.endpointId,
                    attemptId = attemptId,
                    statusCode = event.statusCode
                )
                if (row != null && attemptId != null) {
                    cancelAttemptTimer(current, event.endpointId)
                    current.attempts.remove(event.endpointId)
                    val failure = RadioOutcome.Failure(event.kind, event.statusCode, event.reason)
                    settleAttempt(row, AttemptState.Failed(attemptId, failure))
                }
            }

            is BleEvent.Disconnected -> {
                val row = peers[event.endpointId] ?: return
                record("disconnected", endpointId = event.endpointId)
                // A link that ends before it connected is settled by its own outcome, timeout or cancellation.
                if (row.attempt is AttemptState.Connected) settleAttempt(row, AttemptState.None)
            }

            is BleEvent.Error -> record("radio_error", endpointId = event.endpointId, detail = event.operation)

            // Payload frames belong to the coordinator's sessions.
            is BleEvent.PayloadReceived -> Unit
        }
        refreshSearchTimer(current)
    }

    // ------------------------------------------------------------------ attempts

    private fun onRequestOutcome(current: Run, endpointId: String, attemptId: Long, outcome: RadioOutcome) {
        if (!isCurrent(current)) {
            record("ignored_stale_run", runId = current.runId, endpointId = endpointId, attemptId = attemptId)
            return
        }
        val row = _state.value.peers[endpointId] ?: return
        val attempt = row.attempt
        if (attempt !is AttemptState.Requesting || attempt.attemptId != attemptId) return
        if (outcome is RadioOutcome.Success) {
            putPeer(row.copy(attempt = AttemptState.AwaitingConnection(attemptId)))
            record("connect_submitted", endpointId = endpointId, attemptId = attemptId)
            return
        }
        val failure = outcome as? RadioOutcome.Failure
        record("connect_failed", endpointId = endpointId, attemptId = attemptId, statusCode = failure?.statusCode)
        cancelAttemptTimer(current, endpointId)
        current.attempts.remove(endpointId)
        settleAttempt(row, AttemptState.Failed(attemptId, failure))
    }

    private fun startAttemptTimer(current: Run, endpointId: String, attemptId: Long) {
        current.attemptTimers.remove(endpointId)?.cancel()
        current.attemptTimers[endpointId] =
            current.scope.launch {
                awaitUnpaused(ATTEMPT_TIMEOUT_MS)
                post { onAttemptTimeout(current, endpointId, attemptId) }
            }
    }

    /** Suspends until [totalMs] of unpaused time has elapsed; time spent paused does not count. */
    private suspend fun awaitUnpaused(totalMs: Long) {
        var remaining = totalMs
        while (remaining > 0) {
            paused.first { !it }
            val resumedAt = clock()
            val pausedAgain = withTimeoutOrNull(remaining) { paused.first { it } } != null
            if (!pausedAgain) return
            remaining -= (clock() - resumedAt).coerceIn(0, remaining)
        }
    }

    private fun onAttemptTimeout(current: Run, endpointId: String, attemptId: Long) {
        if (!isCurrent(current)) return
        val row = _state.value.peers[endpointId] ?: return
        if (outgoingAttemptId(row.attempt) != attemptId) return
        record("connect_timeout", endpointId = endpointId, attemptId = attemptId)
        current.attemptTimers.remove(endpointId)
        cancelConnectionAttempt(current, endpointId)
        settleAttempt(row, AttemptState.Failed(attemptId, null, timedOut = true))
    }

    private fun cancelConnectionAttempt(current: Run, endpointId: String) {
        val attempt = current.attempts.remove(endpointId) ?: return
        safely("cancelConnectionAttempt") { radio.cancelConnectionAttempt(attempt) }
    }

    private fun cancelAttemptTimer(current: Run, endpointId: String) {
        current.attemptTimers.remove(endpointId)?.cancel()
    }

    private fun outgoingAttemptId(attempt: AttemptState): Long? = when (attempt) {
        is AttemptState.Requesting -> attempt.attemptId
        is AttemptState.AwaitingConnection -> attempt.attemptId
        else -> null
    }

    // ------------------------------------------------------------------ long search

    private fun refreshSearchTimer(current: Run) {
        val snapshot = _state.value
        val eligible =
            snapshot.phase == RunPhase.ACTIVE &&
                snapshot.discovery is CapabilityState.Running &&
                snapshot.peers.values.none { it.discovered }
        if (!eligible) {
            current.searchTimer?.cancel()
            current.searchTimer = null
            if (snapshot.searchingLong) update { copy(searchingLong = false) }
            return
        }
        if (snapshot.searchingLong || current.searchTimer?.isActive == true) return
        val startedAt = clock()
        current.searchTimer =
            current.scope.launch {
                while (true) {
                    val remaining = startedAt + LONG_SEARCH_MS - clock()
                    if (remaining <= 0) break
                    delay(remaining)
                }
                post { onSearchLong(current) }
            }
    }

    private fun onSearchLong(current: Run) {
        if (!isCurrent(current)) return
        current.searchTimer = null
        val snapshot = _state.value
        val eligible =
            snapshot.phase == RunPhase.ACTIVE &&
                snapshot.discovery is CapabilityState.Running &&
                snapshot.peers.values.none { it.discovered }
        if (eligible) update { copy(searchingLong = true) }
    }

    // ------------------------------------------------------------------ helpers

    private fun post(command: suspend () -> Unit) {
        commands.trySend(command)
    }

    private fun isCurrent(current: Run): Boolean {
        val phase = _state.value.phase
        return run === current && (phase == RunPhase.STARTING || phase == RunPhase.ACTIVE)
    }

    private fun isCurrentOwner(owner: NearbyOwner): Boolean {
        if (owner.id == currentOwnerId) return true
        record("ignored_stale_owner")
        return false
    }

    private fun track(current: Run, task: Deferred<*>) {
        current.tasks.removeAll { it.isCompleted }
        current.tasks += task
    }

    private inline fun update(transform: NearbyRunState.() -> NearbyRunState) {
        _state.value = _state.value.transform()
    }

    private fun NearbyRunState.capability(capability: Capability): CapabilityState = when (capability) {
        Capability.ADVERTISING -> advertising
        Capability.DISCOVERY -> discovery
    }

    private fun setCapability(capability: Capability, value: CapabilityState) {
        update {
            when (capability) {
                Capability.ADVERTISING -> copy(advertising = value)
                Capability.DISCOVERY -> copy(discovery = value)
            }
        }
    }

    private fun putPeer(row: NearbyPeerState) {
        update { copy(peers = peers + (row.endpointId to row)) }
    }

    private fun removePeer(endpointId: String) {
        update { copy(peers = peers - endpointId) }
    }

    /** Applies a settled attempt state; an undiscovered endpoint with no live attempt has no row. */
    private fun settleAttempt(row: NearbyPeerState, attempt: AttemptState) {
        if (!row.discovered) removePeer(row.endpointId) else putPeer(row.copy(attempt = attempt))
    }

    private inline fun safely(operation: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "$operation failed: ${e.message}")
        }
    }

    /** Bounded diagnostic history. [detail] is retained verbatim, including coordinator activation error messages. */
    private fun record(
        event: String,
        runId: Long = _state.value.runId,
        endpointId: String? = null,
        attemptId: Long? = null,
        statusCode: Int? = null,
        detail: String? = null
    ) {
        val entry = TimelineEntry(clock(), runId, event, endpointId, attemptId, statusCode, detail)
        val entries = _timeline.value
        _timeline.value =
            if (entries.size >= TIMELINE_CAPACITY) {
                entries.drop(entries.size - TIMELINE_CAPACITY + 1) + entry
            } else {
                entries + entry
            }
        Log.i(
            TAG,
            buildString {
                append("run=").append(runId).append(' ').append(event)
                endpointId?.let { append(" endpoint=").append(it) }
                attemptId?.let { append(" attempt=").append(it) }
                statusCode?.let { append(" status=").append(it) }
                detail?.let { append(" detail=").append(it) }
            }
        )
    }

    companion object {
        private const val TAG = "NearbyController"

        /** Unpaused time an outgoing attempt may spend between the request and the connection. */
        const val ATTEMPT_TIMEOUT_MS = 30_000L

        /** Discovery time without any endpoint after which [NearbyRunState.searchingLong] is set. */
        const val LONG_SEARCH_MS = 15_000L

        /** Grace for activation/deactivation joins before requesting endpoint stop; not a total cleanup deadline. */
        const val CLOSE_GRACE_MS = 1_500L

        /** Pause before each capability resubmission, indexed by completed attempts; one more failure ends retries. */
        val RETRY_DELAYS_MS = longArrayOf(2_000L, 5_000L, 10_000L)

        const val TIMELINE_CAPACITY = 200

        private const val RETRY_JITTER_MS = 500.0

        /** Capability failures that need the user to act; no automatic retry. */
        private val TERMINAL_KINDS =
            setOf(RadioFailureKind.PERMISSION, RadioFailureKind.LOCATION_SETTING, RadioFailureKind.SERVICE)
    }
}
