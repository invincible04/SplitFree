package com.splitfree.data.nostr.relay

import com.splitfree.data.nostr.NostrClient
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.util.RelayDefaults
import com.splitfree.util.DebugLog as Log
import javax.inject.Inject
import javax.inject.Singleton

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
     * Connect [NostrClient] to the resolved relay set if not already connected.
     *
     * @param forceReconnect if true, disconnects and reconnects even if already connected
     * @return list of relay URLs that were connected
     */
    suspend fun ensureConnected(forceReconnect: Boolean = false): List<String> {
        if (nostrClient.isConnected && !forceReconnect) {
            nostrClient.acquireConnection()
            return nostrClient.currentRelayUrls()
        }

        nostrClient.authSigner = { challenge, relayUrl -> signer.createAuthEvent(challenge, relayUrl) }

        val primaryRelays = resolvePrimaryRelays()
        val onlineRelays = relayHealthMonitor.getOnlineRelays(primaryRelays).ifEmpty { primaryRelays }
        val allRelays = (onlineRelays + RelayDefaults.FALLBACK_RELAYS).distinct()

        nostrClient.connect(allRelays)
        nostrClient.acquireConnection()
        Log.i(TAG, "Connected to ${allRelays.size} relays (${onlineRelays.size} primary + fallbacks)")
        return allRelays
    }

    /** Resolve primary relays: group > default. */
    suspend fun resolvePrimaryRelays(): List<String> {
        val groupRelays = groupRepo.getAll().flatMap { it.relays }.distinct()
        return groupRelays.ifEmpty { RelayDefaults.DEFAULT_RELAYS }
    }

    /** Get all relays that should be connected (primary + fallbacks). */
    suspend fun resolveAllRelays(): List<String> = (resolvePrimaryRelays() + RelayDefaults.FALLBACK_RELAYS).distinct()

    companion object {
        private const val TAG = "RelayConnectionManager"
    }
}
