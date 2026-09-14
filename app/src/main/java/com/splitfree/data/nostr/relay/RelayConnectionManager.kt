package com.splitfree.data.nostr.relay

import com.splitfree.data.nostr.NostrClient
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.util.RelayDefaults
import com.splitfree.util.DebugLog as Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Resolves the relay set (group → default + fallbacks) and ensures
 * [NostrClient] is connected before sync operations begin.
 */
@Singleton
class RelayConnectionManager
@Inject
constructor(
    private val nostrClient: NostrClient,
    private val groupRepo: GroupRepositoryContract,
    private val relayHealthMonitor: RelayHealthMonitor,
    private val signer: EventSigner
) {
    /**
     * Connect [NostrClient] to the resolved relay set if not already connected, waiting up to
     * [CONNECT_TIMEOUT_MS] for at least one relay to reach CONNECTED. The caller holds exactly one
     * connection reference when this returns and none when it throws. A timeout is logged, not
     * thrown: the caller still holds its reference and [NostrClient.connectionState] tells it when
     * a relay does come up.
     *
     * @param forceReconnect if true, re-runs [NostrClient.connect] even when already connected
     *   (relays no longer in the set are dropped, disconnected ones reopened)
     * @return the relay URLs the client was asked to connect (primary + fallbacks), or on the
     *   already-connected fast path [NostrClient.currentRelayUrls]; being listed does not mean connected
     */
    suspend fun ensureConnected(forceReconnect: Boolean = false): List<String> {
        if (nostrClient.isConnected && !forceReconnect) {
            nostrClient.acquireConnection()
            return nostrClient.currentRelayUrls()
        }

        nostrClient.authSigner = { challenge, relayUrl -> signer.createAuthEvent(challenge, relayUrl) }

        val primaryRelays = resolvePrimaryRelays()
        val onlineRelays = relayHealthMonitor.getOnlineRelays(primaryRelays).ifEmpty { primaryRelays }
        // Health probes only order attempts. An offline primary can hold the sole accepted copy;
        // excluding it would prevent its recovery from ever resolving history coverage debt.
        val allRelays = (onlineRelays + primaryRelays + RelayDefaults.FALLBACK_RELAYS).distinct()

        // Take the reference before waiting: the sockets are opening from here on, and a caller
        // cancelled mid-wait would otherwise leave them open with activeUsers == 0 and nobody to
        // release them. The catch below hands the reference back on that path.
        nostrClient.connect(allRelays)
        nostrClient.acquireConnection()

        if (!nostrClient.isConnected) {
            try {
                withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                    nostrClient.connectionState.first { it }
                } ?: Log.w(TAG, "Timed out waiting for relay connection (${CONNECT_TIMEOUT_MS}ms)")
            } catch (e: CancellationException) {
                nostrClient.releaseConnection()
                throw e
            }
        }

        Log.i(
            TAG,
            "Connected to ${allRelays.size} relays (${onlineRelays.size} primary + fallbacks), ready=${nostrClient.isConnected}"
        )
        return allRelays
    }

    /** Resolve primary relays: group > default. */
    suspend fun resolvePrimaryRelays(): List<String> = primaryRelaysOf(groupRepo.getAll())

    /** The primary relay set [groups] imply: each group's relays, or defaults for a group with none. */
    fun primaryRelaysOf(groups: List<Group>): List<String> =
        groups.flatMap { it.relays.ifEmpty { RelayDefaults.DEFAULT_RELAYS } }.distinct()
            .ifEmpty { RelayDefaults.DEFAULT_RELAYS }

    /** Get all relays that should be connected (primary + fallbacks). */
    suspend fun resolveAllRelays(): List<String> = (resolvePrimaryRelays() + RelayDefaults.FALLBACK_RELAYS).distinct()

    companion object {
        private const val TAG = "RelayConnectionManager"
        private const val CONNECT_TIMEOUT_MS = 5_000L
    }
}
