package com.splitfree.domain.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import rust.nostr.sdk.Event
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NIP-59 Gift Wrap service for metadata protection.
 * Design doc Section 8.2 Layer 3.
 *
 * DISABLED: Kotlin bindings for NIP-59 are not yet available in rust-nostr 0.44.2.
 * The official rust-nostr book shows "TODO" for the Kotlin NIP-59 example.
 * See: https://rust-nostr.org/sdk/nips/59.html
 *
 * When Kotlin bindings become available, the correct API is:
 *   Wrap:   gift_wrap(signer, receiverPubkey, rumor: UnsignedEvent, extraTags?)
 *   Unwrap: UnwrappedGift.fromGiftWrap(signer, giftWrapEvent) → .sender(), .rumor()
 *
 * TODO: Enable when rust-nostr publishes Kotlin NIP-59 support.
 */
@Singleton
class GiftWrapService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identityManager: IdentityManager
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("splitfree_settings", Context.MODE_PRIVATE)
    }

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_GIFT_WRAP, false)
        set(value) {
            if (value) Log.w(TAG, "Gift wrap requested but NIP-59 Kotlin bindings not yet available")
            prefs.edit().putBoolean(KEY_GIFT_WRAP, value).apply()
        }

    /**
     * NIP-59 Kotlin bindings not yet available in rust-nostr 0.44.2.
     * Always returns the original event unchanged.
     */
    suspend fun wrapIfEnabled(event: Event): Event = event

    /**
     * NIP-59 Kotlin bindings not yet available in rust-nostr 0.44.2.
     * Always returns null (no unwrapping possible).
     */
    suspend fun tryUnwrap(event: Event): Event? = null

    companion object {
        private const val TAG = "GiftWrapService"
        private const val KEY_GIFT_WRAP = "gift_wrap_enabled"
    }
}
