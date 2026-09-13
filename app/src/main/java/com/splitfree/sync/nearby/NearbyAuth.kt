package com.splitfree.sync.nearby

import com.splitfree.domain.util.hexToBytes
import fr.acinq.secp256k1.Secp256k1
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * BIP-340 mutual authentication over domain-separated, signer-specific session transcripts.
 * Each signature covers both identities and fresh nonces, the protocol version, negotiated capabilities,
 * and the channel token when available. A null token provides no transport channel binding.
 * Both peers must agree on initiator ordering before signing or verifying.
 */
object NearbyAuth {
    private const val TAG = "splitfree-nearby-auth-v2"
    private const val ROLE_INITIATOR: Byte = 0x01
    private const val ROLE_RESPONDER: Byte = 0x02
    private val secureRandom = SecureRandom()

    /** Generates a fresh 32-byte cryptographic challenge encoded as lowercase hex. */
    fun newNonce(): String = ByteArray(32).also { secureRandom.nextBytes(it) }.toHexLower()

    /** Checks the lowercase hex encoding of exactly 32 bytes. */
    fun isHex32(s: String): Boolean = s.length == 64 && s.all { it in "0123456789abcdef" }

    /** Checks the lowercase hex encoding of exactly 64 bytes. */
    fun isHex64(s: String): Boolean = s.length == 128 && s.all { it in "0123456789abcdef" }

    /** Selects the outgoing side as initiator; matching roles use the lexically smaller public key. */
    fun localIsInitiator(myPubkey: String, myIncoming: Boolean, peerPubkey: String, peerIncoming: Boolean): Boolean =
        if (myIncoming != peerIncoming) !myIncoming else myPubkey < peerPubkey

    /**
     * Shared handshake inputs in initiator/responder order; keys and nonces must be 32-byte lowercase hex.
     * [channelToken] is hashed as empty bytes when null. Callers must keep its bytes stable during authentication.
     */
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

    /**
     * Hashes the canonical transcript for the selected signer role; all lengths below are in bytes.
     *
     * ```
     * SHA-256("splitfree-nearby-auth-v2" || 0x00
     *     || version (1) || signer role (1: 0x01 initiator, 0x02 responder)
     *     || initiator pubkey (32) || responder pubkey (32)
     *     || initiator nonce (32) || responder nonce (32)
     *     || SHA-256(channel token or empty bytes)
     *     || SHA-256(sorted capabilities joined by commas, UTF-8))
     * ```
     */
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

    /** Signs the role-specific transcript with BIP-340; the caller must zero [privateKey] after use. */
    fun sign(t: Transcript, signerIsInitiator: Boolean, privateKey: ByteArray): String {
        val hash = transcriptHash(t, signerIsInitiator)
        val aux = ByteArray(32).also { secureRandom.nextBytes(it) }
        return Secp256k1.signSchnorr(hash, privateKey, aux).toHexLower()
    }

    /**
     * Verifies the role-specific signature, returning false for malformed keys or signatures.
     * The caller must select the transcript key corresponding to [signerIsInitiator].
     */
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
