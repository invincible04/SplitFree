package com.splitfree.domain.crypto

import fr.acinq.secp256k1.Secp256k1
import java.security.SecureRandom

/**
 * NIP-59 Gift Wrap — implemented from scratch per spec.
 * https://github.com/nostr-protocol/nips/blob/master/59.md
 *
 * Three-layer encryption:
 *   Rumor (unsigned) → Seal (kind 13, sender key) → Gift Wrap (kind 1059, ephemeral key)
 */
object Nip59 {

    private val secureRandom = SecureRandom()

    /**
     * Wrap a rumor for a specific recipient.
     *
     * @param rumor The inner event (unsigned — sig must be empty)
     * @param senderPrivKey 32-byte sender private key
     * @param recipientPubKey 32-byte x-only recipient public key
     * @return Signed kind 1059 gift wrap event
     */
    fun giftWrap(
        rumor: NostrEvent,
        senderPrivKey: ByteArray,
        recipientPubKey: ByteArray
    ): NostrEvent {
        val senderPubHex = NostrEvent.pubkeyFromPrivkey(senderPrivKey)
        val recipientPubHex = recipientPubKey.toHex()

        // 1. Rumor: compute ID but do NOT sign (provides deniability)
        val rumorWithId = rumor.copy(
            pubkey = senderPubHex,
            id = rumor.copy(pubkey = senderPubHex).computeId().toHex(),
            sig = ""
        )

        // 2. Seal (kind 13): encrypt rumor JSON, sign with sender's real key
        val sealConvKey = Nip44.getConversationKey(senderPrivKey, recipientPubKey)
        val sealContent = Nip44.encrypt(rumorWithId.toJson(), sealConvKey)
        val seal = NostrEvent(
            pubkey = senderPubHex,
            createdAt = randomTimestamp(),
            kind = 13,
            tags = emptyList(), // MUST be empty per spec
            content = sealContent
        ).sign(senderPrivKey)

        // 3. Gift Wrap (kind 1059): encrypt seal JSON with ephemeral key
        val ephemeralPriv = ByteArray(32).also { secureRandom.nextBytes(it) }
        // Ensure valid private key
        while (!Secp256k1.secKeyVerify(ephemeralPriv)) {
            secureRandom.nextBytes(ephemeralPriv)
        }
        val ephemeralPubHex = NostrEvent.pubkeyFromPrivkey(ephemeralPriv)

        val wrapConvKey = Nip44.getConversationKey(ephemeralPriv, recipientPubKey)
        val wrapContent = Nip44.encrypt(seal.toJson(), wrapConvKey)
        val wrap = NostrEvent(
            pubkey = ephemeralPubHex,
            createdAt = randomTimestamp(),
            kind = 1059,
            tags = listOf(listOf("p", recipientPubHex)),
            content = wrapContent
        ).sign(ephemeralPriv)

        // Zero out ephemeral key
        ephemeralPriv.fill(0)

        return wrap
    }

    /**
     * Unwrap a gift wrap event.
     *
     * @param giftWrap The kind 1059 event
     * @param recipientPrivKey 32-byte recipient private key
     * @return Pair of (rumor, senderPubkeyHex) or null if invalid
     */
    fun unwrap(
        giftWrap: NostrEvent,
        recipientPrivKey: ByteArray
    ): Pair<NostrEvent, String>? {
        if (giftWrap.kind != 1059) return null
        if (!giftWrap.verify()) return null

        // Decrypt gift wrap → seal
        val wrapConvKey = Nip44.getConversationKey(recipientPrivKey, giftWrap.pubkey.hexToBytes())
        val sealJson = try { Nip44.decrypt(giftWrap.content, wrapConvKey) } catch (_: Exception) { return null }
        val seal = NostrEvent.fromJson(sealJson) ?: return null

        // Verify seal
        if (!seal.verify()) return null
        if (seal.kind != 13) return null

        // Decrypt seal → rumor
        val sealConvKey = Nip44.getConversationKey(recipientPrivKey, seal.pubkey.hexToBytes())
        val rumorJson = try { Nip44.decrypt(seal.content, sealConvKey) } catch (_: Exception) { return null }
        val rumor = NostrEvent.fromJson(rumorJson) ?: return null

        // Verify sender consistency: rumor.pubkey must match seal.pubkey (prevents impersonation)
        if (rumor.pubkey != seal.pubkey) return null

        return rumor to seal.pubkey
    }

    /** Random timestamp within the past 2 days (some relays reject future timestamps). */
    private fun randomTimestamp(): Long {
        val now = System.currentTimeMillis() / 1000
        val offset = secureRandom.nextLong().ushr(1) % (2 * 86400)
        val ts = now - offset
        // Ensure timestamp is not before Unix epoch
        return if (ts > 0) ts else now
    }
}
