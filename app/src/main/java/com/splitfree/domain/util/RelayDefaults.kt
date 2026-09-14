package com.splitfree.domain.util

/**
 * Single source of truth for relay lists.
 * KNOWN_RELAYS order is append-only; changing order breaks existing invite links.
 */
object RelayDefaults {
    /**
     * Default relays for new groups and fallback when no group relays exist.
     *
     * Each one was verified to accept a kind-30078 write and return it, accept a kind-1059 gift wrap,
     * and serve `#p` gift-wrap queries without NIP-42 auth. Free, different operators.
     */
    val DEFAULT_RELAYS = listOf(
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.primal.net",
        "wss://relay.snort.social",
        "wss://offchain.pub"
    )

    /**
     * Fallback relays, always included in every connection for redundancy.
     * Different operators, different continents, no overlap with [DEFAULT_RELAYS].
     */
    val FALLBACK_RELAYS = listOf(
        "wss://nostr.mom",
        "wss://nostr.oxtr.dev",
        "wss://relay.nostr.wirednet.jp"
    )

    /**
     * Known relays for invite link bitmap encoding.
     * Order is append-only; index-based encoding means reordering breaks existing links.
     */
    val KNOWN_RELAYS = DEFAULT_RELAYS

    const val MAX_GROUP_MEMBERS = 50
}
