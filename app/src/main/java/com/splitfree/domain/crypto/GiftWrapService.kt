package com.splitfree.domain.crypto

import com.splitfree.domain.crypto.nip.Nip59
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.SettingsContract
import com.splitfree.domain.util.hexToBytes
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NIP-59 Gift Wrap service for metadata protection.
 * Wraps outgoing events and unwraps incoming ones; does not manage relay connections or settings.
 */
@Singleton
class GiftWrapService
@Inject
constructor(
    private val identityManager: IdentityContract,
    private val userPreferences: SettingsContract
) {
    val enabled: Boolean get() = userPreferences.giftWrapEnabled

    fun setEnabled(value: Boolean) {
        userPreferences.giftWrapEnabled = value
    }

    /**
     * Wrap a [NostrEvent] in NIP-59 gift wrap for a recipient.
     *
     * @param event the event to wrap
     * @param recipientPubKeyHex 64-char hex public key of the recipient
     * @return gift-wrapped kind-1059 event, or the original event if gift wrap is disabled
     */
    fun wrapIfEnabled(event: NostrEvent, recipientPubKeyHex: String): NostrEvent {
        if (!enabled) return event
        val privKey = identityManager.getPrivateKeyBytes()
        try {
            return Nip59.giftWrap(
                rumor = event.copy(sig = ""),
                senderPrivKey = privKey,
                recipientPubKey = recipientPubKeyHex.hexToBytes()
            )
        } finally {
            privKey.fill(0)
        }
    }

    /**
     * Attempt to unwrap a gift-wrapped event.
     *
     * @param event the incoming event (only kind 1059 is processed)
     * @return pair of (inner rumor, sender pubkey hex), or null if not a gift wrap or decryption fails
     */
    fun tryUnwrap(event: NostrEvent): Pair<NostrEvent, String>? {
        if (event.kind != NostrKind.GIFT_WRAP) return null
        val privKey = identityManager.getPrivateKeyBytes()
        try {
            return Nip59.unwrap(event, privKey)
        } finally {
            privKey.fill(0)
        }
    }
}
