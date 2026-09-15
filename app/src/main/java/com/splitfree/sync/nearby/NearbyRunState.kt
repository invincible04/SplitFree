package com.splitfree.sync.nearby

/** Lifecycle of one activation of the transport, owned by [NearbySessionController]. */
enum class RunPhase {
    /** No run exists and no cleanup is outstanding. */
    IDLE,

    /** A start was requested while the previous run had not reached its cleanup boundary. */
    WAITING_FOR_CLEANUP,

    /** Coordinator activation and event-subscription readiness are in progress before radio starts are scheduled. */
    STARTING,

    /** The run accepts actions; each capability may still be starting, retrying, or failed. */
    ACTIVE,

    /** Cleanup is in progress; user actions for this run are refused. */
    STOPPING,

    /** The run failed; [NearbyRunState.failure] gives the cause. Cleanup may still be outstanding. */
    FAILED
}

/** Controller view of advertising or discovery startup; not a live observation of hardware state. */
sealed class CapabilityState {
    object Stopped : CapabilityState()

    /** A start is scheduled or submitted; its outcome has not been applied to controller state. */
    object Starting : CapabilityState()

    /** Startup succeeded or the SDK reported ALREADY_ACTIVE; no subsequent stop/failure is reflected here. */
    object Running : CapabilityState()

    /** A retry is scheduled; [attempt] counts completed attempts in the current capability-start retry cycle. */
    data class Retrying(val attempt: Int, val failure: RadioOutcome.Failure) : CapabilityState()

    /** No further automatic attempts; the user must act (permission, setting, or manual retry). */
    data class Failed(val failure: RadioOutcome.Failure) : CapabilityState()
}

/** State of the transport connection attempt to one endpoint. */
sealed class AttemptState {
    /** No connection or attempt is tracked; a discovered row is connectable while the run is active. */
    object None : AttemptState()

    /** A request is scheduled or submitted; its outcome has not been applied to controller state. */
    data class Requesting(val attemptId: Long) : AttemptState()

    /** The request was accepted by the platform; waiting for the connection result. */
    data class AwaitingConnection(val attemptId: Long) : AttemptState()

    /**
     * Transport connection observed, not application authentication.
     *
     * - [attemptId] is copied from a pending outgoing row when present; it can be null even for an outgoing link whose
     *   attempt row already settled.
     * - [incoming] is the SDK role, independent of whether an outgoing attempt id was retained.
     */
    data class Connected(val attemptId: Long?, val incoming: Boolean) : AttemptState()

    /** The attempt ended without a connection; retry is available while the endpoint remains discovered. */
    data class Failed(val attemptId: Long, val failure: RadioOutcome.Failure?, val timedOut: Boolean = false) :
        AttemptState()
}

/**
 * One endpoint the run knows about, from discovery, an outgoing attempt, or an incoming connection.
 *
 * @property name unverified name from discovery or connection initiation, or null when unavailable.
 * @property discovered whether discovery currently reports the endpoint.
 */
data class NearbyPeerState(
    val endpointId: String,
    val name: String?,
    val discovered: Boolean,
    val attempt: AttemptState = AttemptState.None
)

/** Why a run ended in [RunPhase.FAILED]. */
sealed class RunFailure {
    /** Neither advertising nor discovery could be started; both capability failures are in the run state. */
    object NoCapability : RunFailure()

    /** The transport reported a condition that invalidates the run. */
    data class Transport(val fault: TransportFault) : RunFailure()

    /** The coordinator could not be prepared for the group. */
    data class Coordinator(val message: String) : RunFailure()
}

/**
 * Observable state of the controller.
 *
 * - Protocol progress per peer is published separately by [NearbySessionCoordinator.state]; observers join the two by
 *   endpoint id.
 *
 * @property searchingLong discovery has remained running with no currently discovered endpoints for the search
 *   threshold.
 */
data class NearbyRunState(
    val runId: Long = 0,
    val phase: RunPhase = RunPhase.IDLE,
    val groupId: String? = null,
    val advertising: CapabilityState = CapabilityState.Stopped,
    val discovery: CapabilityState = CapabilityState.Stopped,
    val peers: Map<String, NearbyPeerState> = emptyMap(),
    val searchingLong: Boolean = false,
    val failure: RunFailure? = null
)

/**
 * Controller diagnostic entry intended for operational metadata.
 *
 * - Strings are not sanitized by this model; callers must exclude secrets and peer/group contents.
 * - Coordinator failures can supply exception messages.
 */
data class TimelineEntry(
    val atMs: Long,
    val runId: Long,
    val event: String,
    val endpointId: String? = null,
    val attemptId: Long? = null,
    val statusCode: Int? = null,
    /** Diagnostic reason, operation, fault kind, or exception message; not constrained to machine-readable values. */
    val detail: String? = null
)
