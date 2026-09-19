package com.splitfree.domain.model.group

import com.splitfree.domain.util.hexToBytes
import com.splitfree.domain.util.toHex
import fr.acinq.secp256k1.Secp256k1
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.serialization.Serializable

/**
 * Payload of a `key_revocation` event: retires an identity, optionally naming its replacement.
 *
 * The event signature authorizes retirement by [oldPubkey]. A named [newPubkey] can replace its roster
 * seat, but balances transfer only when [successorProof] also authorizes the pair for this group.
 *
 * @property successorProof BIP-340 signature by [newPubkey] over [successorMessage]; empty when
 *   successor authorization was not supplied
 */
@Serializable
data class KeyRevocation(
    val oldPubkey: String,
    val newPubkey: String = "",
    val reason: String = "",
    val successorProof: String = ""
) {
    /**
     * Checks that the authenticated author is the retiring identity and any replacement is a distinct,
     * lowercase hex pubkey. Does not verify the event signature, membership or successor proof.
     */
    fun isAuthorizedBy(authorPubkey: String): Boolean = authorPubkey == oldPubkey &&
        oldPubkey.isNotEmpty() &&
        (newPubkey.isEmpty() || PUBKEY_HEX.matches(newPubkey)) &&
        newPubkey != oldPubkey

    /**
     * Verifies the successor's signature over [groupId] and the ordered `(old, new)` identity pair.
     * Proves successor authorization, not that one person holds both keys; cannot be reused across groups.
     */
    fun provesSuccessor(groupId: String): Boolean {
        if (newPubkey.isEmpty() || !PUBKEY_HEX.matches(newPubkey) || !SIGNATURE_HEX.matches(successorProof)) {
            return false
        }
        return try {
            Secp256k1.verifySchnorr(
                successorProof.hexToBytes(),
                successorMessage(groupId, oldPubkey, newPubkey),
                newPubkey.hexToBytes()
            )
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private val PUBKEY_HEX = Regex("^[0-9a-f]{64}$")
        private val SIGNATURE_HEX = Regex("^[0-9a-f]{128}$")
        private const val PROOF_DOMAIN = "splitfree/key_revocation/successor"

        /** SHA-256 of the domain tag, group ID and ordered identity pair, separated by newlines. */
        fun successorMessage(groupId: String, oldPubkey: String, newPubkey: String): ByteArray = MessageDigest
            .getInstance("SHA-256")
            .digest("$PROOF_DOMAIN\n$groupId\n$oldPubkey\n$newPubkey".toByteArray(Charsets.UTF_8))

        /**
         * Sign [successorMessage] with [newPrivateKey], the replacement identity's private key, for a
         * revocation of [oldPubkey] in favour of [newPubkey] within [groupId]. The caller zeroes the key.
         */
        fun proveSuccessor(groupId: String, oldPubkey: String, newPubkey: String, newPrivateKey: ByteArray): String {
            val auxRand = ByteArray(32).also { SecureRandom().nextBytes(it) }
            return Secp256k1
                .signSchnorr(successorMessage(groupId, oldPubkey, newPubkey), newPrivateKey, auxRand)
                .toHex()
        }
    }
}
