package com.splitfree.domain.util

/**
 * Single source of truth for relay lists. [DEFAULT_RELAYS] and [FALLBACK_RELAYS] are subsets of
 * [KNOWN_RELAYS], whose order is append-only because the invite link encodes known relays by index.
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
     * Every relay the app vouches for; the relay editor offers these. Every entry meets the
     * [DEFAULT_RELAYS] acceptance criteria. Append-only: the index is the bit position in the invite-link
     * relay bitmap (16 bits), so reordering breaks existing links.
     */
    val KNOWN_RELAYS = listOf(
        "wss://purplerelay.com",
        "wss://nos.lol",
        "wss://relay.primal.net",
        "wss://relay.snort.social",
        "wss://offchain.pub",
        "wss://nostr.data.haus",
        "wss://nostr.oxtr.dev",
        "wss://relay.nostr.wirednet.jp",
        "wss://nostr-pub.wellorder.net",
        "wss://nostr.sathoarder.com",
        "wss://nostr.bitcoiner.social",
        "wss://nostr.mom"
    )

    const val MAX_GROUP_MEMBERS = 50
}
