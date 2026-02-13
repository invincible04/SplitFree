package com.splitfree.domain.crypto

import com.splitfree.data.util.CompressionUtil
import fr.acinq.secp256k1.Secp256k1
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles group symmetric encryption using NIP-44 v2 — no SDK.
 *
 * Derives a deterministic secp256k1 keypair from the shared group key using
 * HMAC-SHA256 as a KDF, then uses our from-scratch Nip44 for encrypt/decrypt.
 */
@Singleton
class GroupEncryption
    @Inject
    constructor() {
        fun generateGroupKey(): String {
            val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
            return java.util.Base64
                .getEncoder()
                .encodeToString(key)
        }

        fun encrypt(
            plaintext: String,
            groupKeyBase64: String,
        ): String {
            require(plaintext.isNotEmpty()) { "Cannot encrypt empty plaintext" }
            val conversationKey = deriveConversationKey(groupKeyBase64)
            try {
                val raw = plaintext.toByteArray()
                val payload =
                    if (CompressionUtil.shouldCompress(raw)) {
                        val compressed = CompressionUtil.compress(raw)
                        if (compressed != null) {
                            COMPRESSED_PREFIX +
                                java.util.Base64
                                    .getEncoder()
                                    .encodeToString(compressed)
                        } else {
                            plaintext
                        }
                    } else {
                        plaintext
                    }
                return Nip44.encrypt(payload, conversationKey)
            } finally {
                conversationKey.fill(0)
            }
        }

        fun decrypt(
            encrypted: String,
            groupKeyBase64: String,
        ): String {
            val conversationKey = deriveConversationKey(groupKeyBase64)
            try {
                val decoded = Nip44.decrypt(encrypted, conversationKey)
                if (decoded.startsWith(COMPRESSED_PREFIX)) {
                    val b64 = decoded.removePrefix(COMPRESSED_PREFIX)
                    val compressed =
                        java.util.Base64
                            .getDecoder()
                            .decode(b64)
                    val decompressed = CompressionUtil.decompress(compressed)
                    if (decompressed != null) return String(decompressed)
                }
                return decoded
            } finally {
                conversationKey.fill(0)
            }
        }

        /**
         * Derive a NIP-44 conversation key from the group key.
         * Uses HMAC-SHA256 to derive two valid private keys, then computes
         * the conversation key via Nip44.getConversationKey.
         */
        private fun deriveConversationKey(groupKeyBase64: String): ByteArray {
            val keyBytes =
                java.util.Base64
                    .getDecoder()
                    .decode(groupKeyBase64)
            require(keyBytes.size == 32) { "Group key must be 32 bytes" }

            val senderPriv = deriveValidPrivateKey(keyBytes, "splitfree-sender")
            val recipientPriv = deriveValidPrivateKey(keyBytes, "splitfree-recipient")
            // Get recipient's public key (32-byte x-only)
            val recipientPub = Secp256k1.pubkeyCreate(recipientPriv).copyOfRange(1, 33)

            val convKey = Nip44.getConversationKey(senderPriv, recipientPub)
            senderPriv.fill(0)
            recipientPriv.fill(0)
            return convKey
        }

        private fun deriveValidPrivateKey(
            ikm: ByteArray,
            context: String,
        ): ByteArray {
            for (counter in 0..255) {
                val info = "$context-$counter".toByteArray()
                val derived = hmacSha256(ikm, info)
                if (Secp256k1.secKeyVerify(derived)) return derived
            }
            error("Failed to derive valid private key")
        }

        private fun hmacSha256(
            key: ByteArray,
            data: ByteArray,
        ): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }

        companion object {
            private const val COMPRESSED_PREFIX = "SF_LZ4:"
        }
    }
