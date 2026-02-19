package com.splitfree.domain.repository

import com.splitfree.domain.crypto.NostrEvent
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

    /** Reactive connection state — emits whenever any relay connects/disconnects. */
    val connectionState: StateFlow<Boolean>

    /** Stream of verified, deduplicated incoming events from all connected relays. */
    val incomingEvents: SharedFlow<NostrEvent>

    /** NIP-42 AUTH signer — set before [connect] to enable relay authentication. */
    var authSigner: ((challenge: String, relayUrl: String) -> NostrEvent)?

    /** @return current list of relay URLs this client is connected to */
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
     * Fetch events matching a group filter. Subscribes temporarily, collects until
     * EOSE from all relays (or timeout), then closes the subscription.
     *
     * @param groupId target group UUID
     * @param since unix timestamp; 0 to fetch all history
     * @param myPubkey if non-null, also fetches kind-1059 gift wraps addressed to this pubkey
     * @return deduplicated list of verified events
     */
    suspend fun fetchEvents(groupId: String, since: Long, myPubkey: String? = null): List<NostrEvent>

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

    /** No-op — messages flow automatically via [incomingEvents]. */
    fun startListening()
}
