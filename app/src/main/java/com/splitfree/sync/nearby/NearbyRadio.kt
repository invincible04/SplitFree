package com.splitfree.sync.nearby

import kotlinx.coroutines.flow.StateFlow

/** Categories the controller acts on; the adapter maps platform status codes and exceptions onto them. */
enum class RadioFailureKind {
    /** The platform reports the operation as already running for this client. */
    ALREADY_ACTIVE,

    /** A required permission is missing or access was refused with SecurityException. */
    PERMISSION,

    /** Location services are off on a platform path that requires them. */
    LOCATION_SETTING,

    /** Radio, strategy, or API-order failure eligible for a bounded capability-start retry. */
    RADIO,

    /** The named endpoint is unknown, not connected, rejected, or its channel failed. */
    ENDPOINT,

    /** One payload could not be sent or received; the connection may still be usable. */
    PAYLOAD,

    /** A mapped network-unavailable or API-connection-in-use status; not automatically retried by the controller. */
    SERVICE,

    /** Unmapped failure; capability startup uses the same bounded retry policy as [RADIO]. */
    UNKNOWN
}

/** SDK task outcome, or an immediate outcome when submission fails or is skipped. */
sealed class RadioOutcome {
    /** The platform accepted the operation. For a connection request this means sent, not connected. */
    object Success : RadioOutcome()

    /** The SDK cancelled its task, or a request was skipped because its identity was cancelled or already used. */
    object Cancelled : RadioOutcome()

    data class Failure(val kind: RadioFailureKind, val statusCode: Int?, val message: String) : RadioOutcome()
}

/**
 * A run-invalidating condition, such as event-buffer overflow.
 *
 * - The fault StateFlow retains the latest value until reset, independently of event-buffer capacity; conflation can
 *   skip intermediate faults.
 */
data class TransportFault(val kind: String, val detail: String, val sequence: Long)

/**
 * Single-use local request identity, created before queueing so cancellation can precede SDK submission.
 *
 * - Distinct from both a connected link's [NearbyConnection] capability and the SDK authentication token.
 */
class NearbyConnectionAttempt(val endpointId: String) {
    // Only the owning adapter accesses these fields, under its submission/cancellation lock.
    internal var cancelled = false
    internal var submission: Long? = null
}

/**
 * Radio control for one Nearby Connections client.
 *
 * - Start/request outcomes return to the caller; connection and payload lifecycle use the non-replaying [events]
 *   stream.
 * - Subscribe before submission.
 * - Local stop or attempt cancellation does not complete an outstanding SDK task.
 * - The run owner keeps submitted operations observed through actual settlement and finishes cleanup before starting
 *   the next run.
 * - Lifecycle ownership rejects stale submissions, but does not identify successive incoming links sharing one
 *   advertising callback.
 * - SDK terminal-callback ordering remains a constraint on same-submission reuse.
 * - Diagnostic task failures and events already buffered may still be observed after local stop.
 */
interface NearbyRadio : NearbyTransport {
    /** Advertises the unverified local key prefix; success reports SDK startup, not continued radio health. */
    suspend fun startAdvertising(): RadioOutcome

    /** Starts discovery for this application; success reports SDK startup, not endpoint availability. */
    suspend fun startDiscovery(): RadioOutcome

    /**
     * Submits [attempt] at most once.
     *
     * - Success is the SDK request outcome, not a connection or authentication result; lifecycle events may arrive
     *   before or after it.
     * - Missing progress requires a caller-owned timeout.
     * - A failed/cancelled SDK task revokes a still-pending attempt, but does not end an already-connected link.
     * - A repeated call returns Cancelled without altering the original submission or substituting for its outcome.
     */
    suspend fun requestConnection(attempt: NearbyConnectionAttempt): RadioOutcome

    /**
     * Revokes a pending or not-yet-submitted attempt without ending an already-connected or replacement link.
     *
     * - Does not cancel or settle the submitted SDK task; its original waiter still observes the SDK outcome.
     */
    fun cancelConnectionAttempt(attempt: NearbyConnectionAttempt)

    fun stopAdvertising()

    fun stopDiscovery()

    /**
     * Clears local connection/request ownership, advances the run boundary, and requests SDK endpoint cleanup.
     *
     * - Connected links attempt a Disconnected event; pending links are removed silently.
     * - Clears [fault].
     * - Does not await outstanding SDK tasks or replace the explicit advertising/discovery stop calls.
     */
    fun stopAllEndpoints()

    /** The current run-invalidating condition, or null. Cleared by [stopAllEndpoints]. */
    val fault: StateFlow<TransportFault?>
}
