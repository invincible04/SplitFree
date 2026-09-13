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
     * Build a signed Nostr kind-30078 event for SplitFree under a fresh, random relay address.
     *
     * @param groupId target group UUID, used in the `g` tag and as the `d` tag prefix
     * @param eventType value for the `t` tag (e.g. `expense`, `settlement`, `group_meta`)
     * @param encryptedContent NIP-44 encrypted payload
     * @param expenseUuid optional logical expense UUID for the `x` tag; revisions of one expense share it
     * @param recipientPubkey optional pubkey for a `p` tag when the payload is addressed to exactly one
     *   member (per-member `key_rotation` envelopes), so relays and couriers can route it without
     *   decrypting it
     * @param createdAt explicit `created_at`, or null for now. A corrective control event uses it to
     *   sort strictly after the plan event it supersedes, whatever the wall clock says.
     * @return signed [NostrEvent] with computed ID and BIP-340 signature
     */
    fun createSignedEvent(
        groupId: String,
        eventType: String,
        encryptedContent: String,
        expenseUuid: String? = null,
        recipientPubkey: String? = null,
        createdAt: Long? = null
    ): NostrEvent = createSignedCommandEvent(
        groupId = groupId,
        eventType = eventType,
        encryptedContent = encryptedContent,
        expenseUuid = expenseUuid,
        createdAt = createdAt,
        recipientPubkey = recipientPubkey
    )

    /**
     * Build a signed Nostr kind-30078 event whose relay address is bound to one command.
     *
     * Kind 30078 is addressable (NIP-01): a relay keeps one event per `(pubkey, kind, d)`. The `d` tag is
     * therefore `"$groupId:$eventType:$commandId"`, unique per command, so an original expense and each
     * of its corrections and deletions occupy distinct addresses and none can evict another. The logical
     * expense identity stays in the `x` tag. Signing the same command twice with the same [createdAt]
     * yields the same event id, which lets a retried command be recognised by its address.
     *
     * @param commandId stable caller-supplied identifier of the command, or a random UUID
     * @param createdAt explicit `created_at`, or null for now
     * @param recipientPubkey optional `p` tag recipient, see [createSignedEvent]
     */
    fun createSignedCommandEvent(
        groupId: String,
        eventType: String,
        encryptedContent: String,
        expenseUuid: String? = null,
        commandId: String = java.util.UUID.randomUUID().toString(),
        createdAt: Long? = null,
        recipientPubkey: String? = null
    ): NostrEvent {
        require(commandId.isNotBlank()) { "Command ID must not be blank" }
        val privKey = identityManager.getPrivateKeyBytes()
        try {
            val pubHex = identityManager.getPublicKeyHex()
            val tags =
                buildList {
                    add(listOf("d", relayAddress(groupId, eventType, commandId)))
                    add(listOf("g", groupId)) // group membership tag for filtering
                    add(listOf("t", eventType))
                    expenseUuid?.let { add(listOf("x", it)) }
                    recipientPubkey?.let { add(listOf("p", it)) }
                }

            return NostrEvent(
                pubkey = pubHex,
                createdAt = createdAt ?: (System.currentTimeMillis() / 1000),
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

    companion object {
        /** The `d` tag value under which a command of [eventType] in [groupId] is published. */
        fun relayAddress(groupId: String, eventType: String, commandId: String): String =
            "$groupId:$eventType:$commandId"
    }
}
