package com.splitfree.domain.repository

/**
 * Domain contract for user preferences (privacy toggles).
 */
interface SettingsContract {
    /** Whether NIP-59 gift wrapping is enabled for outgoing events. */
    var giftWrapEnabled: Boolean

    /** User's chosen display name (shared with group members via group_meta). */
    var displayName: String
}
