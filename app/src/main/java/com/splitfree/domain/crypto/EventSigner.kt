package com.splitfree.domain.crypto

import com.splitfree.domain.repository.IdentityContract
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates and signs valid Nostr events (NIP-01 compliant).
 * Event ID = SHA-256 of serialized event. Signature = BIP-340 Schnorr.
 */
@Singleton
class EventSigner
@Inject
constructor(private val identityManager: IdentityContract) {
    /**
     * Build a signed Nostr kind-30078 event for SplitFree.
     *
     * @param groupId target group UUID, used in `g` and `d` tags
     * @param eventType value for the `t` tag (e.g. `expense`, `settlement`, `group_meta`)
     * @param encryptedContent NIP-44 encrypted payload
     * @param expenseUuid optional UUID for the `x` tag and d-tag uniqueness
     * @param recipientPubkey optional pubkey for a `p` tag when the payload is addressed to exactly one
     *   member (per-member `key_rotation` envelopes), so relays and couriers can route it without
     *   decrypting it
     * @return signed [NostrEvent] with computed ID and BIP-340 signature
     */
    fun createSignedEvent(
        groupId: String,
        eventType: String,
        encryptedContent: String,
        expenseUuid: String? = null,
        recipientPubkey: String? = null
    ): NostrEvent {
        val privKey = identityManager.getPrivateKeyBytes()
        try {
            val pubHex = identityManager.getPublicKeyHex()

            // Kind 30078 is ADDRESSABLE (NIP-01): relays keep only the latest event
            // per (pubkey, kind, d-tag). We MUST make d unique per event, otherwise
            // each new expense overwrites the previous one on the relay.
            val dTagValue = if (expenseUuid !=
                null
            ) {
                "$groupId:$expenseUuid"
            } else {
                "$groupId:${java.util.UUID.randomUUID()}"
            }
            val tags =
                buildList {
                    add(listOf("d", dTagValue))
                    add(listOf("g", groupId)) // group membership tag for filtering
                    add(listOf("t", eventType))
                    expenseUuid?.let { add(listOf("x", it)) }
                    recipientPubkey?.let { add(listOf("p", it)) }
                }

            return NostrEvent(
                pubkey = pubHex,
                createdAt = System.currentTimeMillis() / 1000,
                kind = NostrKind.APP_SPECIFIC,
                tags = tags,
                content = encryptedContent
            ).sign(privKey)
        } finally {
            privKey.fill(0)
        }
    }

    /** Verify a received event's signature. */
    fun verify(event: NostrEvent): Boolean = event.verify()

    /**
     * NIP-42: Create a signed AUTH event for relay authentication.
     *
     * @param challenge the challenge string from the relay's AUTH message
     * @param relayUrl the relay URL to bind the auth to
     * @return signed kind-22242 event
     */
    fun createAuthEvent(challenge: String, relayUrl: String): NostrEvent {
        val privKey = identityManager.getPrivateKeyBytes()
        try {
            return NostrEvent(
                pubkey = identityManager.getPublicKeyHex(),
                createdAt = System.currentTimeMillis() / 1000,
                kind = NostrKind.AUTH,
                tags = listOf(listOf("challenge", challenge), listOf("relay", relayUrl)),
                content = ""
            ).sign(privKey)
        } finally {
            privKey.fill(0)
        }
    }

    /**
     * NIP-09: Create a kind-5 deletion event requesting relays delete the given event IDs.
     *
     * @param eventIds list of event IDs to request deletion for
     * @param reason optional human-readable reason
     * @return signed kind-5 event
     */
    fun createDeletionEvent(eventIds: List<String>, reason: String = ""): NostrEvent {
        val privKey = identityManager.getPrivateKeyBytes()
        try {
            val tags = eventIds.map { listOf("e", it) }
            return NostrEvent(
                pubkey = identityManager.getPublicKeyHex(),
                createdAt = System.currentTimeMillis() / 1000,
                kind = NostrKind.DELETION,
                tags = tags,
                content = reason
            ).sign(privKey)
        } finally {
            privKey.fill(0)
        }
    }
}
