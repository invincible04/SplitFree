package com.splitfree.data.nostr

import com.splitfree.util.DebugLog as Log
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GiftWrapService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unified relay selection and connection logic for all sync paths.
 * Ensures consistent behavior across SyncWorker, MidnightSyncWorker, and ForegroundSyncService.
 */
@Singleton
class RelayConnectionManager
    @Inject
    constructor(
        private val nostrClient: NostrClient,
        private val groupRepo: GroupRepository,
        private val relayHealthMonitor: RelayHealthMonitor,
        private val giftWrap: GiftWrapService,
        private val signer: EventSigner,
    ) {
        /**
         * Ensure NostrClient is connected with the correct relay set.
         * Logic (same for all callers):
         * 1. Custom relays override group relays (user preference)
         * 2. Fall back to group relays, then DEFAULT_RELAYS
         * 3. Health-filter the primary relays
         * 4. Always append FALLBACK_RELAYS for redundancy
         *
         * @param forceReconnect If true, disconnects and reconnects even if already connected.
         * @return The relay URLs that were connected to.
         */
        suspend fun ensureConnected(forceReconnect: Boolean = false): List<String> {
            if (nostrClient.isConnected && !forceReconnect) {
                nostrClient.acquireConnection()
                return nostrClient.currentRelayUrls()
            }

            nostrClient.authSigner = { challenge, relayUrl -> signer.createAuthEvent(challenge, relayUrl) }

            val primaryRelays = resolvePrimaryRelays()
            val onlineRelays = relayHealthMonitor.getOnlineRelays(primaryRelays).ifEmpty { primaryRelays }
            val allRelays = (onlineRelays + RelayConfig.FALLBACK_RELAYS).distinct()

            nostrClient.connect(allRelays)
            nostrClient.acquireConnection()
            Log.i(TAG, "Connected to ${allRelays.size} relays (${onlineRelays.size} primary + fallbacks)")
            return allRelays
        }

        /** Resolve primary relays: custom > group > default. */
        suspend fun resolvePrimaryRelays(): List<String> {
            val custom = giftWrap.getCustomRelays()
            if (custom.isNotEmpty()) return custom

            val groupRelays = groupRepo.getAll().flatMap { it.relays }.distinct()
            return groupRelays.ifEmpty { RelayConfig.DEFAULT_RELAYS }
        }

        /** Get all relays that should be connected (primary + fallbacks). */
        suspend fun resolveAllRelays(): List<String> {
            return (resolvePrimaryRelays() + RelayConfig.FALLBACK_RELAYS).distinct()
        }

        companion object {
            private const val TAG = "RelayConnectionManager"
        }
    }
