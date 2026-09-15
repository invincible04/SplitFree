package com.splitfree.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitfree.R
import com.splitfree.sync.nearby.AttemptState
import com.splitfree.sync.nearby.CapabilityState
import com.splitfree.sync.nearby.NearbyOwner
import com.splitfree.sync.nearby.NearbyPeerState
import com.splitfree.sync.nearby.NearbyRunState
import com.splitfree.sync.nearby.NearbySessionController
import com.splitfree.sync.nearby.NearbySessionCoordinator
import com.splitfree.sync.nearby.NearbySessionsState
import com.splitfree.sync.nearby.NearbyWire
import com.splitfree.sync.nearby.PeerPhase
import com.splitfree.sync.nearby.PeerProgress
import com.splitfree.sync.nearby.RadioFailureKind
import com.splitfree.sync.nearby.RadioOutcome
import com.splitfree.sync.nearby.RunFailure
import com.splitfree.sync.nearby.RunPhase
import com.splitfree.ui.util.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * What the screen's headline says.
 *
 * - Declared in priority order: when several apply, the first wins.
 * - [SYNCING] is set by any row in an active protocol phase, so one peer finishing never hides another still
 *   transferring.
 */
enum class Headline {
    /** At least one peer session is authenticating, opening the group, comparing or transferring. */
    SYNCING,

    /** A connection attempt is in flight, or a transport connection exists without protocol progress yet. */
    CONNECTING,

    /** The run lists at least one peer; the count is the number of non-result rows. */
    FOUND,

    /** The run is active and discovery is running, starting or awaiting retry. */
    SEARCHING,

    /** The run is starting or waiting for the previous run's cleanup. */
    STARTING,

    /** The run ended on its own; [NearbySyncUiState.runFailure] says why. */
    FAILED,

    /** No run, a run being stopped, or an active run that is neither searching nor found anyone. */
    IDLE
}

/** The one thing a peer row offers, derived from the transport attempt and the protocol phase. */
enum class RowAction {
    /** Discovered, not connected, run active: the Sync button requests a connection. */
    SYNC,

    /** An attempt is in flight: show Cancel. */
    CONNECTING,

    /** Connected with active protocol work or no progress reported yet; no button. */
    BUSY,

    /** Connected with a result (up to date, incomplete, waiting or terminal); show it, no button. */
    DONE,

    /** The attempt failed and the endpoint is still discovered: the Retry button requests again. */
    RETRY,

    /** Nothing can be done from this row. */
    NONE
}

/** What the run-level notice invites the user to do. */
enum class NoticeAction {
    NONE,

    /** Request permissions again, or open app settings when the route selects settings recovery. */
    GRANT_PERMISSION,

    /** Open the system location settings. */
    OPEN_LOCATION_SETTINGS,

    /** Request startup, or retry failed capabilities if the same group's run is already active. */
    RETRY
}

/**
 * Run-level message under the headline.
 *
 * @property warning render as a warning rather than guidance.
 */
data class NearbyNotice(
    val message: UiMessage,
    val action: NoticeAction = NoticeAction.NONE,
    val warning: Boolean = false
)

/**
 * One row on the screen: a peer the run lists, or a retained result for an endpoint absent from the run.
 *
 * @property displayName the unverified advertised name, or a generic label for an endpoint without one.
 * @property verifiedPubkey the peer key verified by the protocol, not proof of group membership; null if unverified.
 * @property recent a result kept by the coordinator for an endpoint absent from the run; never actionable.
 */
data class NearbyPeerRow(
    val endpointId: String,
    val displayName: UiMessage,
    val verifiedPubkey: String?,
    val discovered: Boolean,
    val attempt: AttemptState,
    val progress: PeerProgress?,
    val action: RowAction,
    val status: UiMessage?,
    val recent: Boolean = false
)

/**
 * UI state for the nearby sync screen: a projection of the controller's run state and the coordinator's
 * protocol progress, joined by endpoint id.
 *
 * @property enabled the user's intent to search and be discoverable; false after an explicit stop.
 * @property notice run-level guidance or failure, or null.
 * @property progressVisible any row is in an active protocol phase.
 */
data class NearbySyncUiState(
    val enabled: Boolean = true,
    val phase: RunPhase = RunPhase.IDLE,
    val advertising: CapabilityState = CapabilityState.Stopped,
    val discovery: CapabilityState = CapabilityState.Stopped,
    val searchingLong: Boolean = false,
    val runFailure: RunFailure? = null,
    val headline: Headline = Headline.IDLE,
    val rows: List<NearbyPeerRow> = emptyList(),
    val notice: NearbyNotice? = null,
    val progressVisible: Boolean = false
) {
    /** Discovery is running or trying to run; the radar pulses. */
    val searching: Boolean
        get() = phase == RunPhase.ACTIVE && discovery.isTrying()

    /** Rows the run lists, excluding retained results. */
    val peerCount: Int
        get() = rows.count { !it.recent }
}

/**
 * Translates screen lifecycle and user intent into [NearbySessionController] calls and projects controller and
 * coordinator state into [NearbySyncUiState].
 *
 * - Owns no protocol or transport state.
 * - With a nonempty group id, startup is requested when the destination is STARTED, route prerequisites are satisfied
 *   and the user has not explicitly stopped.
 * - User intent is saved across recreation; lifecycle and prerequisite flags are not.
 * - A later lifecycle start requests a run, not restoration of previous connections.
 * - Controller commands and cleanup are asynchronous, so forwarding a request does not imply the run has already
 *   changed phase.
 */
@HiltViewModel
class NearbySyncViewModel
@Inject
constructor(
    private val savedStateHandle: SavedStateHandle,
    private val controller: NearbySessionController,
    coordinator: NearbySessionCoordinator
) : ViewModel() {
    private val groupId: String = savedStateHandle["groupId"] ?: ""
    private val owner: NearbyOwner = controller.acquire()
    private val enabled: StateFlow<Boolean> = savedStateHandle.getStateFlow(KEY_ENABLED, true)
    private var screenStarted = false
    private var prerequisitesSatisfied = false

    val uiState: StateFlow<NearbySyncUiState> =
        combine(enabled, controller.state, coordinator.state) { on, run, sessions -> project(on, run, sessions) }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                project(enabled.value, controller.state.value, coordinator.state.value)
            )

    /** ON_START: requests startup when user intent, prerequisites and group id allow it. */
    fun onScreenStarted() {
        screenStarted = true
        startIfReady()
    }

    /** ON_STOP or disposal: requests cleanup without clearing intent, allowing a later ready start. */
    fun onScreenStopped() {
        screenStarted = false
        controller.stop(owner)
    }

    /** The route reports whether every permission is granted and Bluetooth is on. */
    fun onPrerequisites(satisfied: Boolean) {
        if (prerequisitesSatisfied == satisfied) return
        prerequisitesSatisfied = satisfied
        if (satisfied) {
            startIfReady()
        } else {
            // Published IDLE can still have a queued start; stop must not depend on the observed run phase.
            controller.stop(owner)
        }
    }

    /** Pauses connection-attempt timeouts only; protocol handshake and transfer watchdogs keep their own clock. */
    fun setPaused(paused: Boolean) = controller.setForegroundPaused(owner, paused)

    /** User intent: search and be discoverable. */
    fun start() {
        savedStateHandle[KEY_ENABLED] = true
        startIfReady()
    }

    /** User intent: stop; stays stopped across recreation until [start]. */
    fun stop() {
        savedStateHandle[KEY_ENABLED] = false
        controller.stop(owner)
    }

    fun connect(endpointId: String) = controller.connect(owner, endpointId)

    fun cancelAttempt(endpointId: String) = controller.cancelAttempt(owner, endpointId)

    /** Back navigation: requests cleanup before leaving; [onCleared] separately releases ownership. */
    fun leave() = controller.stop(owner)

    override fun onCleared() {
        controller.release(owner)
    }

    private fun startIfReady() {
        if (screenStarted && prerequisitesSatisfied && enabled.value && groupId.isNotEmpty()) {
            controller.start(owner, groupId)
        }
    }

    private fun project(intent: Boolean, run: NearbyRunState, sessions: NearbySessionsState): NearbySyncUiState {
        val rows = rows(run, sessions)
        return NearbySyncUiState(
            enabled = intent,
            phase = run.phase,
            advertising = run.advertising,
            discovery = run.discovery,
            searchingLong = run.searchingLong,
            runFailure = run.failure,
            headline = headline(run, rows),
            rows = rows,
            notice = notice(run, rows),
            progressVisible = rows.any { it.progress?.phase?.isActiveProtocol() == true }
        )
    }

    /** Every run peer joined with its protocol progress, then retained results for endpoints absent from the run. */
    private fun rows(run: NearbyRunState, sessions: NearbySessionsState): List<NearbyPeerRow> {
        val live = run.peers.values.map { peer -> row(run, peer, sessions.peers[peer.endpointId]) }
        val sameGroup = sessions.groupId == null || sessions.groupId == groupId
        val results =
            if (!sameGroup) {
                emptyList()
            } else {
                sessions.peers.values
                    .filter { it.endpointId !in run.peers && it.phase.isTerminal() }
                    .map { progress ->
                        NearbyPeerRow(
                            endpointId = progress.endpointId,
                            displayName = UiMessage.Res(R.string.nearby_phone),
                            verifiedPubkey = progress.peerPubkey,
                            discovered = false,
                            attempt = AttemptState.None,
                            progress = progress,
                            action = RowAction.NONE,
                            status = statusFor(progress),
                            recent = true
                        )
                    }
            }
        return live + results
    }

    private fun row(run: NearbyRunState, peer: NearbyPeerState, progress: PeerProgress?): NearbyPeerRow {
        val attempt = peer.attempt
        val connectable = peer.discovered && run.phase == RunPhase.ACTIVE
        val liveSession = progress != null && !progress.phase.isTerminal()
        val connecting = UiMessage.Res(R.string.nearby_connecting)
        val action =
            when (attempt) {
                is AttemptState.Requesting, is AttemptState.AwaitingConnection -> RowAction.CONNECTING
                is AttemptState.Connected ->
                    if (progress == null || progress.phase.isActiveProtocol()) RowAction.BUSY else RowAction.DONE
                is AttemptState.Failed -> if (connectable) RowAction.RETRY else RowAction.NONE
                AttemptState.None -> if (connectable && !liveSession) RowAction.SYNC else RowAction.NONE
            }
        val status =
            when (attempt) {
                is AttemptState.Requesting, is AttemptState.AwaitingConnection -> connecting
                is AttemptState.Connected -> progress?.let(::statusFor) ?: connecting
                is AttemptState.Failed -> attemptFailure(attempt)
                AttemptState.None -> progress?.let(::statusFor)
            }
        val name = peer.name?.takeIf { it.isNotBlank() }
        return NearbyPeerRow(
            endpointId = peer.endpointId,
            displayName = name?.let(UiMessage::Raw) ?: UiMessage.Res(R.string.nearby_phone),
            verifiedPubkey = progress?.peerPubkey,
            discovered = peer.discovered,
            attempt = attempt,
            progress = progress,
            action = action,
            status = status
        )
    }

    private fun attemptFailure(attempt: AttemptState.Failed): UiMessage = when {
        attempt.timedOut -> UiMessage.Res(R.string.nearby_attempt_timed_out)
        attempt.failure != null -> UiMessage.Res(R.string.nearby_connection_failed, attempt.failure.message)
        else -> UiMessage.Res(R.string.nearby_connection_failed_unknown)
    }

    private fun headline(run: NearbyRunState, rows: List<NearbyPeerRow>): Headline = when {
        rows.any { it.progress?.phase?.isActiveProtocol() == true } -> Headline.SYNCING
        rows.any { it.isConnecting() } -> Headline.CONNECTING
        rows.any { !it.recent } -> Headline.FOUND
        run.phase == RunPhase.ACTIVE && run.discovery.isTrying() -> Headline.SEARCHING
        run.phase == RunPhase.STARTING || run.phase == RunPhase.WAITING_FOR_CLEANUP -> Headline.STARTING
        run.phase == RunPhase.FAILED -> Headline.FAILED
        else -> Headline.IDLE
    }

    /** Failures first, then partial capability states, then search guidance while nobody has been found. */
    private fun notice(run: NearbyRunState, rows: List<NearbyPeerRow>): NearbyNotice? {
        val peers = rows.any { !it.recent }
        val failure = run.failure
        if (run.phase == RunPhase.FAILED) {
            return when (failure) {
                is RunFailure.Transport ->
                    retryNotice(UiMessage.Res(R.string.nearby_notice_transport, failure.fault.detail))
                is RunFailure.Coordinator ->
                    retryNotice(UiMessage.Res(R.string.nearby_notice_coordinator, failure.message))
                RunFailure.NoCapability, null ->
                    capabilityNotice(run.discovery) ?: capabilityNotice(run.advertising)
                        ?: retryNotice(UiMessage.Res(R.string.nearby_headline_failed))
            }
        }
        if (run.phase == RunPhase.ACTIVE) {
            capabilityNotice(run.discovery)?.let { return it }
            capabilityNotice(run.advertising)?.let { return it }
            if (run.discovery is CapabilityState.Retrying && run.advertising == CapabilityState.Running) {
                return NearbyNotice(UiMessage.Res(R.string.nearby_notice_search_restarting))
            }
            if (run.advertising is CapabilityState.Retrying && run.discovery == CapabilityState.Running) {
                return NearbyNotice(UiMessage.Res(R.string.nearby_notice_advertise_restarting))
            }
            if (!peers && run.discovery.isTrying()) {
                val id = if (run.searchingLong) R.string.nearby_search_long else R.string.nearby_searching_hint
                return NearbyNotice(UiMessage.Res(id))
            }
        }
        return null
    }

    private fun capabilityNotice(capability: CapabilityState): NearbyNotice? {
        val failure = (capability as? CapabilityState.Failed)?.failure ?: return null
        return failureNotice(failure)
    }

    private fun failureNotice(failure: RadioOutcome.Failure): NearbyNotice = when (failure.kind) {
        RadioFailureKind.PERMISSION ->
            NearbyNotice(UiMessage.Res(R.string.nearby_notice_permission), NoticeAction.GRANT_PERMISSION, true)
        RadioFailureKind.LOCATION_SETTING ->
            NearbyNotice(UiMessage.Res(R.string.nearby_notice_location), NoticeAction.OPEN_LOCATION_SETTINGS, true)
        RadioFailureKind.SERVICE -> retryNotice(UiMessage.Res(R.string.nearby_notice_service))
        RadioFailureKind.ALREADY_ACTIVE,
        RadioFailureKind.RADIO,
        RadioFailureKind.ENDPOINT,
        RadioFailureKind.PAYLOAD,
        RadioFailureKind.UNKNOWN -> retryNotice(UiMessage.Res(R.string.nearby_notice_radio, failure.message))
    }

    private fun retryNotice(message: UiMessage) = NearbyNotice(message, NoticeAction.RETRY, warning = true)

    /** An attempt in flight, or a transport connection the protocol has not reported on yet. */
    private fun NearbyPeerRow.isConnecting(): Boolean =
        action == RowAction.CONNECTING || (attempt is AttemptState.Connected && progress == null)

    private fun statusFor(p: PeerProgress): UiMessage = when (p.phase) {
        PeerPhase.AUTHENTICATING -> UiMessage.Res(R.string.nearby_authenticating)
        PeerPhase.OPENING_GROUP -> UiMessage.Res(R.string.nearby_opening_group)
        PeerPhase.COMPARING -> UiMessage.Res(R.string.nearby_comparing)
        PeerPhase.TRANSFERRING -> UiMessage.Res(R.string.nearby_transferring, p.stats.applied, p.stats.sent)
        PeerPhase.WAITING_DEPENDENCY ->
            UiMessage.Plural(R.plurals.nearby_waiting_dependency, p.stats.deferred, p.stats.deferred)
        PeerPhase.UP_TO_DATE -> UiMessage.Res(R.string.nearby_up_to_date, p.stats.applied, p.stats.sent)
        PeerPhase.INCOMPLETE -> {
            val failed = p.stats.rejected + p.stats.busy + p.stats.unresolved + p.stats.held
            UiMessage.Plural(R.plurals.nearby_incomplete, failed, failed)
        }
        PeerPhase.UNSUPPORTED_PEER -> UiMessage.Res(R.string.nearby_unsupported_peer)
        PeerPhase.AUTH_FAILED -> UiMessage.Res(R.string.nearby_peer_auth_failed)
        PeerPhase.UNAUTHORIZED -> UiMessage.Res(R.string.nearby_unauthorized)
        PeerPhase.INTERRUPTED ->
            if (p.closeReason == NearbyWire.CLOSE_TIMEOUT) {
                UiMessage.Res(R.string.nearby_handshake_timeout)
            } else {
                UiMessage.Plural(R.plurals.nearby_interrupted, p.stats.applied, p.stats.applied)
            }
        PeerPhase.CLOSED -> UiMessage.Res(R.string.nearby_sync_complete)
    }

    companion object {
        /** Saved-state key for the user's intent to run; defaults to true when the destination opens. */
        const val KEY_ENABLED = "nearbyEnabled"
    }
}

/** Handshake and reconciliation phases during which the session is doing work. */
fun PeerPhase.isActiveProtocol(): Boolean = this == PeerPhase.AUTHENTICATING ||
    this == PeerPhase.OPENING_GROUP ||
    this == PeerPhase.COMPARING ||
    this == PeerPhase.TRANSFERRING

/** Closed-session results; UP_TO_DATE, INCOMPLETE and WAITING_DEPENDENCY can still receive work on the live link. */
fun PeerPhase.isTerminal(): Boolean = this == PeerPhase.UNSUPPORTED_PEER ||
    this == PeerPhase.AUTH_FAILED ||
    this == PeerPhase.UNAUTHORIZED ||
    this == PeerPhase.INTERRUPTED ||
    this == PeerPhase.CLOSED

/** Running, or an attempt to run is in progress or scheduled. */
fun CapabilityState.isTrying(): Boolean =
    this == CapabilityState.Running || this == CapabilityState.Starting || this is CapabilityState.Retrying
