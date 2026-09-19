package com.splitfree.domain.repository

import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.sync.ConnectionStatus
import com.splitfree.domain.model.sync.FetchResult
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Domain contract for Nostr relay operations.
 *
 * Only `wss://` URLs are accepted to prevent unencrypted relay connections.
 */
interface NostrClientContract {
    /** True if at least one relay is connected. */
    val isConnected: Boolean

    /** Reactive connection state; true whenever at least one relay is connected. */
    val connectionState: StateFlow<Boolean>

    /** Monotonic socket-open generation, including recovery masked by another connected relay. */
    val connectionGeneration: StateFlow<Long>

    /** Reactive connection status: connecting, connected, or offline. */
    val connectionStatus: StateFlow<ConnectionStatus>

    /** Stream of verified, deduplicated incoming events from all connected relays. */
    val incomingEvents: SharedFlow<NostrEvent>

    /** NIP-42 AUTH signer; set before [connect] to enable relay authentication. */
    var authSigner: ((challenge: String, relayUrl: String) -> NostrEvent)?

    /** @return configured relay URLs, including relays that are not currently connected */
    fun currentRelayUrls(): List<String>

    /** Increment the active-user ref count (prevents disconnect). */
    fun acquireConnection()

    /** Decrement the ref count; disconnects when it reaches zero. */
    fun releaseConnection()

    /**
     * Connect to the given relay URLs, disconnecting any stale relays not in the new list.
     * @param relayUrls list of `wss://` relay URLs; non-wss URLs are silently rejected
     */
    suspend fun connect(relayUrls: List<String>)

    /**
     * Reopens configured relays in the disconnected state, resetting their reconnect budget.
     * Connected or connecting relays are left alone; aggregate connectivity can hide a disconnected peer.
     *
     * @return URLs asked to reconnect, not a guarantee that their sockets opened
     */
    suspend fun reopenDisconnectedRelays(): List<String>

    /** Disconnect all relays and clear state. */
    fun disconnect()

    /** Add a single relay and start collecting its events. */
    fun addRelay(url: String)

    /**
     * Publish an event to all connected relays.
     * @return true if at least one relay accepted the event
     */
    suspend fun publish(event: NostrEvent): Boolean

    /**
     * Parse JSON into a [NostrEvent] and publish it.
     * @return true if at least one relay accepted the event
     */
    suspend fun publishJson(eventJson: String): Boolean

    /**
     * Fetches matching events from the client's relay set until requested history completes or times out.
     * Disconnected relays remain incomplete; available sockets use temporary subscriptions.
     *
     * @param groupId target group UUID
     * @param since Unix timestamp; 0 requests all available history
     * @param myPubkey if non-null, also requests kind-1059 gift wraps addressed to this pubkey
     * @return verified, deduplicated events and per-relay history coverage; see [FetchResult.complete]
     */
    suspend fun fetchEvents(groupId: String, since: Long, myPubkey: String? = null): FetchResult

    /** Fetch each requested relay from its own history window; unavailable URLs remain incomplete. */
    suspend fun fetchEventsByRelay(
        groupId: String,
        sinceByRelay: Map<String, Long>,
        myPubkey: String? = null
    ): FetchResult

    /**
     * Fetch only direct-mode event IDs for self-heal comparison.
     *
     * This intentionally excludes gift-wrap events (kind 1059) because their IDs do not
     * correspond to the stored inner event IDs.
     *
     * @param groupId target group UUID
     * @param since unix timestamp; 0 to fetch all history
     * @param myPubkey optional caller pubkey (reserved for future filter compatibility)
     * @return deduplicated set of event IDs for kind-30078 events
     */
    suspend fun fetchEventIds(groupId: String, since: Long, myPubkey: String? = null): Set<String>

    /**
     * Fetch kind-1059 gift wrap events addressed to a specific pubkey (last 24h).
     * @param recipientPubHex 64-char hex public key of the recipient
     */
    suspend fun fetchGiftWraps(recipientPubHex: String): List<NostrEvent>

    /** Subscribe to real-time events for a group. */
    suspend fun subscribe(groupId: String, since: Long, myPubkey: String? = null)

    /** Close the subscription for a group. */
    suspend fun unsubscribe(groupId: String)

    /** Close all active subscriptions. */
    suspend fun unsubscribeAll()
}
