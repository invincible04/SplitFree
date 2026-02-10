package com.splitfree.domain.crypto

import android.util.Base64
import com.splitfree.data.util.CompressionUtil
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Nip44Version
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.nip44Decrypt
import rust.nostr.sdk.nip44Encrypt
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles group symmetric encryption using NIP-44 v2 primitives.
 *
 * Derives a deterministic secp256k1 keypair from the shared group key using
 * HMAC-SHA256 (as a KDF) to ensure the derived bytes are uniformly distributed
 * and overwhelmingly likely to be valid private keys.
 *
 * Compression is handled at the plaintext level using a magic prefix convention
 * to avoid binary payloads that would require an extra Base64 encoding layer.
 *
 * Pin rust-nostr version (0.44.2) — internal NIP-44 changes could break this approach.
 */
@Singleton
class GroupEncryption @Inject constructor() {

    fun generateGroupKey(): String {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(key, Base64.NO_WRAP)
    }

    fun encrypt(plaintext: String, groupKeyBase64: String): String {
        val (senderKeys, recipientPub) = deriveKeyPair(groupKeyBase64)
        val raw = plaintext.toByteArray()
        // Compress at the string level to avoid binary→Base64 overhead
        val payload = if (CompressionUtil.shouldCompress(raw)) {
            val compressed = CompressionUtil.compress(raw)
            if (compressed != null) {
                COMPRESSED_PREFIX + Base64.encodeToString(compressed, Base64.NO_WRAP)
            } else {
                plaintext
            }
        } else {
            plaintext
        }
        return nip44Encrypt(senderKeys.secretKey(), recipientPub, payload, Nip44Version.V2)
    }

    fun decrypt(encrypted: String, groupKeyBase64: String): String {
        val (senderKeys, recipientPub) = deriveKeyPair(groupKeyBase64)
        val decoded = nip44Decrypt(senderKeys.secretKey(), recipientPub, encrypted)
        // Check for compressed payload (new format with string prefix)
        if (decoded.startsWith(COMPRESSED_PREFIX)) {
            val b64 = decoded.removePrefix(COMPRESSED_PREFIX)
            val compressed = Base64.decode(b64, Base64.NO_WRAP)
            val decompressed = CompressionUtil.decompress(compressed)
            if (decompressed != null) return String(decompressed)
        }
        // Try legacy format (Base64 with binary compression flag)
        return try {
            val payload = Base64.decode(decoded, Base64.NO_WRAP)
            if (payload.isNotEmpty() && payload[0] == 0x01.toByte()) {
                val decompressed = CompressionUtil.decompress(payload.copyOfRange(1, payload.size))
                decompressed?.let { String(it) } ?: decoded
            } else if (payload.isNotEmpty() && payload[0] == 0x00.toByte()) {
                String(payload, 1, payload.size - 1)
            } else {
                decoded
            }
        } catch (_: Exception) {
            decoded // plain JSON string — backward compatible
        }
    }

    /**
     * Derive a deterministic keypair from the group key using HMAC-SHA256 as a KDF.
     */
    private fun deriveKeyPair(groupKeyBase64: String): Pair<Keys, PublicKey> {
        val keyBytes = Base64.decode(groupKeyBase64, Base64.NO_WRAP)
        require(keyBytes.size == 32) { "Group key must be 32 bytes" }

        val senderHex = deriveValidPrivateKey(keyBytes, "splitfree-sender")
        val recipientHex = deriveValidPrivateKey(keyBytes, "splitfree-recipient")

        val senderKeys = Keys.parse(senderHex)
        val recipientKeys = Keys.parse(recipientHex)
        return senderKeys to recipientKeys.publicKey()
    }

    private fun deriveValidPrivateKey(ikm: ByteArray, context: String): String {
        for (counter in 0..255) {
            val info = "$context-$counter".toByteArray()
            val derived = hmacSha256(ikm, info)
            try {
                Keys.parse(derived.toHexString())
                return derived.toHexString()
            } catch (_: Exception) {
                // Extremely unlikely (~2^-224 per attempt); try next counter
            }
        }
        error("Failed to derive valid private key (should never happen)")
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }

    companion object {
        /** Magic prefix for compressed payloads — avoids binary flags that need Base64 wrapping */
        private const val COMPRESSED_PREFIX = "SF_LZ4:"
    }
}
