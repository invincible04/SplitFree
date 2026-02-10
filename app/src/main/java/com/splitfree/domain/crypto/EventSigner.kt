package com.splitfree.domain.crypto

import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.NostrSigner
import rust.nostr.sdk.Tag
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates and signs valid Nostr events (NIP-01 compliant).
 * Event ID = SHA256 of serialized event. Signature = Schnorr (BIP-340).
 */
@Singleton
class EventSigner @Inject constructor(
    private val identityManager: IdentityManager
) {
    /**
     * Build a signed Nostr kind-30078 event for SplitFree.
     * Returns the signed Event with correct SHA256 id and Schnorr signature.
     */
    suspend fun createSignedEvent(
        groupId: String,
        eventType: String,
        encryptedContent: String,
        expenseUuid: String? = null
    ): Event {
        val keys = identityManager.getKeys()
        val signer = NostrSigner.keys(keys)
        val tags = buildList {
            add(Tag.parse(listOf("d", groupId)))
            add(Tag.parse(listOf("t", eventType)))
            expenseUuid?.let { add(Tag.parse(listOf("e", it))) }
        }
        return EventBuilder(Kind(30078u), encryptedContent)
            .tags(tags)
            .sign(signer)
    }

    /**
     * Verify a received event's signature.
     */
    fun verify(event: Event): Boolean {
        return try {
            event.verify()
            true
        } catch (_: Exception) {
            false
        }
    }
}
