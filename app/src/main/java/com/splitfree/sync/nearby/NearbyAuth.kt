package com.splitfree.sync.nearby

import com.splitfree.domain.util.hexToBytes
import fr.acinq.secp256k1.Secp256k1
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Channel-bound mutual authentication for one nearby session.
 *
 * Both peers send a [Hello] carrying a fresh nonce, then each signs the same canonical transcript
 * with its long-term Nostr key and sends an [Auth]. The transcript covers:
 *
 * ```
 * SHA-256( "splitfree-nearby-auth-v2" || 0x00
 *        || version (1 byte)
 *        || role byte of the signer (0x01 initiator, 0x02 responder)
 *        || initiator pubkey (32) || responder pubkey (32)
 *        || initiator nonce (32)  || responder nonce (32)
 *        || SHA-256(channel token bytes)
 *        || SHA-256(canonical capabilities) )
 * ```
 *
 * The channel token is Nearby's raw authentication token for this connection; it is identical on both
 * ends and different for every other connection, so a signature copied from one connection cannot
 * complete authentication on another. Domain separation and fixed-size inputs keep this from acting
 * as a Nostr signing oracle. Only existing SHA-256 and BIP-340 primitives are used.
 *
 * Roles are chosen deterministically from the two Hellos so both sides agree without a third
 * message: the outgoing side of the Nearby connection initiates; if both sides report the same
 * role (simultaneous connection attempts), the lexically smaller pubkey initiates.
 */
object NearbyAuth {
    private const val TAG = "splitfree-nearby-auth-v2"
    private const val ROLE_INITIATOR: Byte = 0x01
    private const val ROLE_RESPONDER: Byte = 0x02
    private val secureRandom = SecureRandom()

    fun newNonce(): String = ByteArray(32).also { secureRandom.nextBytes(it) }.toHexLower()

    fun isHex32(s: String): Boolean = s.length == 64 && s.all { it in "0123456789abcdef" }

    fun isHex64(s: String): Boolean = s.length == 128 && s.all { it in "0123456789abcdef" }

    /** True when the local side initiates, given both Hellos. Both peers compute the same answer. */
    fun localIsInitiator(myPubkey: String, myIncoming: Boolean, peerPubkey: String, peerIncoming: Boolean): Boolean =
        if (myIncoming != peerIncoming) !myIncoming else myPubkey < peerPubkey

    /** Everything both sides must agree on before signing. */
    data class Transcript(
        val initiatorPubkey: String,
        val responderPubkey: String,
        val initiatorNonce: String,
        val responderNonce: String,
        val channelToken: ByteArray?,
        val capabilities: Set<String>
    ) {
        init {
            require(isHex32(initiatorPubkey) && isHex32(responderPubkey)) { "pubkey must be 32 bytes hex" }
            require(isHex32(initiatorNonce) && isHex32(responderNonce)) { "nonce must be 32 bytes hex" }
        }
    }

    fun transcriptHash(t: Transcript, signerIsInitiator: Boolean): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(TAG.toByteArray(Charsets.UTF_8))
        md.update(0x00)
        md.update(NearbyWire.PROTOCOL_VERSION.toByte())
        md.update(if (signerIsInitiator) ROLE_INITIATOR else ROLE_RESPONDER)
        md.update(t.initiatorPubkey.hexToBytes())
        md.update(t.responderPubkey.hexToBytes())
        md.update(t.initiatorNonce.hexToBytes())
        md.update(t.responderNonce.hexToBytes())
        md.update(sha256(t.channelToken ?: ByteArray(0)))
        md.update(sha256(t.capabilities.sorted().joinToString(",").toByteArray(Charsets.UTF_8)))
        return md.digest()
    }

    /** Sign the transcript with [privateKey]; the caller zeroes the key. */
    fun sign(t: Transcript, signerIsInitiator: Boolean, privateKey: ByteArray): String {
        val hash = transcriptHash(t, signerIsInitiator)
        val aux = ByteArray(32).also { secureRandom.nextBytes(it) }
        return Secp256k1.signSchnorr(hash, privateKey, aux).toHexLower()
    }

    fun verify(t: Transcript, signerIsInitiator: Boolean, signerPubkey: String, sigHex: String): Boolean {
        if (!isHex64(sigHex) || !isHex32(signerPubkey)) return false
        return try {
            Secp256k1.verifySchnorr(
                sigHex.hexToBytes(),
                transcriptHash(t, signerIsInitiator),
                signerPubkey.hexToBytes()
            )
        } catch (_: Exception) {
            false
        }
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun ByteArray.toHexLower(): String = joinToString("") { "%02x".format(it) }
}
