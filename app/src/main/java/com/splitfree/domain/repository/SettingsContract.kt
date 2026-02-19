package com.splitfree.domain.repository

/**
 * Domain contract for user preferences (privacy toggles, relay config).
 * Provides access to user-configurable privacy toggles and relay settings.
 */
interface SettingsContract {
    /** Whether NIP-59 gift wrapping is enabled for outgoing events. */
    var giftWrapEnabled: Boolean

    /** @return user-configured custom relay URLs (only `wss://` are kept) */
    fun getCustomRelays(): List<String>

    /** Persist custom relay URLs. Non-`wss://` URLs are silently filtered. */
    fun setCustomRelays(relays: List<String>)
}
