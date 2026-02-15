package com.splitfree.domain.crypto

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates and signs valid Nostr events (NIP-01 compliant) — no SDK.
 * Event ID = SHA256 of serialized event. Signature = BIP-340 Schnorr.
 */
@Singleton
class EventSigner
    @Inject
    constructor(
        private val identityManager: IdentityManager,
    ) {
        /**
         * Build a signed Nostr kind-30078 event for SplitFree.
         */
        fun createSignedEvent(
            groupId: String,
            eventType: String,
            encryptedContent: String,
            expenseUuid: String? = null,
        ): NostrEvent {
            val privKey = identityManager.getPrivateKeyBytes()
            try {
                val pubHex = identityManager.getPublicKeyHex()

                // Kind 30078 is ADDRESSABLE (NIP-01): relays keep only the latest event
                // per (pubkey, kind, d-tag). We MUST make d unique per event, otherwise
                // each new expense overwrites the previous one on the relay.
                val dTagValue = if (expenseUuid != null) "$groupId:$expenseUuid" else "$groupId:${java.util.UUID.randomUUID()}"
                val tags =
                    buildList {
                        add(listOf("d", dTagValue))
                        add(listOf("g", groupId)) // group membership tag for filtering
                        add(listOf("t", eventType))
                        expenseUuid?.let { add(listOf("x", it)) }
                    }

                return NostrEvent(
                    pubkey = pubHex,
                    createdAt = System.currentTimeMillis() / 1000,
                    kind = 30078,
                    tags = tags,
                    content = encryptedContent,
                ).sign(privKey)
            } finally {
                privKey.fill(0)
            }
        }

        /** Verify a received event's signature. */
        fun verify(event: NostrEvent): Boolean = event.verify()

        /**
         * NIP-42: Create a signed AUTH event for relay authentication.
         */
        fun createAuthEvent(
            challenge: String,
            relayUrl: String,
        ): NostrEvent {
            val privKey = identityManager.getPrivateKeyBytes()
            try {
                return NostrEvent(
                    pubkey = identityManager.getPublicKeyHex(),
                    createdAt = System.currentTimeMillis() / 1000,
                    kind = 22242,
                    tags = listOf(listOf("challenge", challenge), listOf("relay", relayUrl)),
                    content = "",
                ).sign(privKey)
            } finally {
                privKey.fill(0)
            }
        }

        /**
         * NIP-09: Create a kind 5 deletion event requesting relays delete the given event IDs.
         */
        fun createDeletionEvent(
            eventIds: List<String>,
            reason: String = "",
        ): NostrEvent {
            val privKey = identityManager.getPrivateKeyBytes()
            try {
                val tags = eventIds.map { listOf("e", it) }
                return NostrEvent(
                    pubkey = identityManager.getPublicKeyHex(),
                    createdAt = System.currentTimeMillis() / 1000,
                    kind = 5,
                    tags = tags,
                    content = reason,
                ).sign(privKey)
            } finally {
                privKey.fill(0)
            }
        }
    }
