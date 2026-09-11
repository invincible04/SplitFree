package com.splitfree.domain.crypto

/**
 * Nostr event kind constants defined by NIPs.
 *
 * Using these instead of magic numbers makes the codebase searchable
 * and ensures consistency across relay filters, event creation, and validation.
 */
object NostrKind {
    /** NIP-09: Event deletion request. */
    const val DELETION = 5

    /** NIP-59: Seal (inner encrypted layer of gift wrap). */
    const val SEAL = 13

    /** NIP-42: Relay authentication challenge-response. */
    const val AUTH = 22242

    /** NIP-59: Gift wrap (outer encrypted envelope). */
    const val GIFT_WRAP = 1059

    /** NIP-78: App-specific data, SplitFree's primary event kind. */
    const val APP_SPECIFIC = 30078
}
