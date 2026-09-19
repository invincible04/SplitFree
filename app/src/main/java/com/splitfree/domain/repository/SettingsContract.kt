package com.splitfree.domain.repository

/**
 * Privacy preferences and durable, identity-scoped display-name requests.
 */
interface SettingsContract {
    /** Whether NIP-59 gift wrapping is enabled for outgoing events. */
    var giftWrapEnabled: Boolean

    /** User's chosen display name (shared with group members via group_meta). */
    var displayName: String

    /** Identity-specific requested name, falling back to the shared preference before initialization. */
    fun displayNameFor(identityPubkey: String): String = getDisplayNameIntent(identityPubkey)?.name ?: displayName

    fun getDisplayNameIntent(identityPubkey: String): DisplayNameIntent? = null

    fun saveDisplayNameIntent(identityPubkey: String, name: String): DisplayNameIntent =
        error("Durable display-name intents are unavailable")

    /**
     * Same-person revocation only: refresh the successor from the retiring identity's latest intent.
     * Caller must hold the identity-operation/settings locks and keep the old identity active, unless
     * repairing a missing successor intent after legacy promotion. Never replace an active successor's intent.
     */
    fun transferDisplayNameIntent(oldPubkey: String, newPubkey: String): DisplayNameIntent

    fun initializeDisplayNameIntent(identityPubkey: String): DisplayNameIntent =
        getDisplayNameIntent(identityPubkey) ?: saveDisplayNameIntent(identityPubkey, displayName)
}
