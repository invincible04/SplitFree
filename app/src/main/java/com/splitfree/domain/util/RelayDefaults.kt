package com.splitfree.domain.util

/**
 * Single source of truth for relay lists.
 * KNOWN_RELAYS order is append-only; changing order breaks existing invite links.
 */
object RelayDefaults {
    /**
     * Default relays for new groups and fallback when no group relays exist.
     *
     * Every entry meets the acceptance criteria: accepts a kind-30078 write and returns it on
     * read-back, accepts a kind-1059 gift wrap, serves `#p` queries without NIP-42 auth, and is free.
     */
    val DEFAULT_RELAYS = listOf(
        "wss://purplerelay.com",
        "wss://nos.lol",
        "wss://relay.primal.net",
        "wss://relay.snort.social",
        "wss://offchain.pub"
    )

    /**
     * Fallback relays, always included in every connection for redundancy.
     * Every entry meets the [DEFAULT_RELAYS] acceptance criteria and runs on different operators
     * and hosts from [DEFAULT_RELAYS].
     */
    val FALLBACK_RELAYS = listOf(
        "wss://nostr.data.haus",
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
