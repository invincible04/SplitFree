package com.splitfree.domain.crypto

import fr.acinq.secp256k1.Secp256k1
import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * NIP-44 v2 encryption/decryption — implemented from scratch per spec.
 * https://github.com/nostr-protocol/nips/blob/master/44.md
 *
 * Algorithm: secp256k1 ECDH → HKDF-SHA256 → ChaCha20 (RFC 8439) + HMAC-SHA256
 * Wire format: base64(0x02 || nonce32 || ciphertext || hmac32)
 */
object Nip44 {

    private val secureRandom = SecureRandom()

    // --- Conversation Key (long-term, per user pair) ---

    /**
     * Derive conversation key from private key A and public key B.
     * conv(a, B) == conv(b, A) — symmetric.
     *
     * @param privateKey 32-byte secret key
     * @param publicKey 32-byte x-only pubkey (will be converted to 33-byte compressed)
     */
    fun getConversationKey(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        // Convert 32-byte x-only to 33-byte compressed (02 prefix — even y)
        val pubkey33 = if (publicKey.size == 32) byteArrayOf(0x02) + publicKey else publicKey
        // ECDH: scalar multiplication → raw uncompressed point (65 bytes: 04||x||y)
        // MUST use pubKeyTweakMul, NOT ecdh() — ecdh() SHA256-hashes the output
        val sharedPoint = Secp256k1.pubKeyTweakMul(pubkey33, privateKey)
        // Extract raw 32-byte x-coordinate (unhashed, per NIP-44 spec)
        val sharedX = sharedPoint.copyOfRange(1, 33)
        // HKDF-Extract: salt="nip44-v2", IKM=sharedX
        return hkdfExtract("nip44-v2".toByteArray(Charsets.UTF_8), sharedX)
    }

    // --- Encrypt ---

    fun encrypt(plaintext: String, conversationKey: ByteArray): String {
        val nonce = ByteArray(32).also { secureRandom.nextBytes(it) }
        return encrypt(plaintext, conversationKey, nonce)
    }

    /**
     * Encrypt with explicit nonce (for testing with test vectors).
     */
    fun encrypt(plaintext: String, conversationKey: ByteArray, nonce: ByteArray): String {
        val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)
        require(plaintextBytes.size in 1..65535) { "plaintext length out of range" }

        // Message keys via HKDF-Expand
        val keys = hkdfExpand(conversationKey, nonce, 76)
        val chachaKey = keys.copyOfRange(0, 32)
        val chaChaNonce = keys.copyOfRange(32, 44) // 12-byte nonce
        val hmacKey = keys.copyOfRange(44, 76)

        // Pad
        val padded = pad(plaintextBytes)

        // ChaCha20 (RFC 8439, counter=0)
        // ChaCha7539Engine starts counter at 0 by default, matching NIP-44 spec
        val ciphertext = chacha20(chachaKey, chaChaNonce, padded)

        // HMAC-SHA256(key=hmacKey, message=nonce||ciphertext)
        val mac = hmacSha256(hmacKey, nonce + ciphertext)

        // Wire: base64(0x02 || nonce || ciphertext || mac)
        val payload = byteArrayOf(0x02) + nonce + ciphertext + mac
        return java.util.Base64.getEncoder().encodeToString(payload)
    }

    // --- Decrypt ---

    fun decrypt(payload: String, conversationKey: ByteArray): String {
        // Validate base64 length range: 132..87472
        require(payload.isNotEmpty() && payload[0] != '#') { "unsupported encryption version" }
        require(payload.length in 132..87472) { "invalid payload size" }

        val data = java.util.Base64.getDecoder().decode(payload)
        // Validate decoded length: 99..65603
        require(data.size in 99..65603) { "invalid data size" }
        require(data[0] == 0x02.toByte()) { "unsupported NIP-44 version: ${data[0]}" }

        val nonce = data.copyOfRange(1, 33)
        val ciphertext = data.copyOfRange(33, data.size - 32)
        val mac = data.copyOfRange(data.size - 32, data.size)

        // Derive message keys
        val keys = hkdfExpand(conversationKey, nonce, 76)
        val chachaKey = keys.copyOfRange(0, 32)
        val chaChaNonce = keys.copyOfRange(32, 44)
        val hmacKey = keys.copyOfRange(44, 76)

        // Verify HMAC first (authenticate-then-decrypt)
        val expectedMac = hmacSha256(hmacKey, nonce + ciphertext)
        require(MessageDigest.isEqual(mac, expectedMac)) { "invalid MAC" }

        // Decrypt
        val padded = chacha20(chachaKey, chaChaNonce, ciphertext)

        // Unpad
        return unpad(padded)
    }

    // --- Padding (custom power-of-two, per NIP-44 spec) ---

    fun calcPaddedLen(unpaddedLen: Int): Int {
        require(unpaddedLen in 1..65536)
        if (unpaddedLen <= 32) return 32
        val nextPower = Integer.highestOneBit(unpaddedLen - 1) shl 1
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * ((unpaddedLen - 1) / chunk + 1)
    }

    private fun pad(plaintext: ByteArray): ByteArray {
        val paddedLen = calcPaddedLen(plaintext.size)
        val result = ByteArray(2 + paddedLen)
        result[0] = (plaintext.size shr 8).toByte()
        result[1] = (plaintext.size and 0xFF).toByte()
        System.arraycopy(plaintext, 0, result, 2, plaintext.size)
        return result
    }

    private fun unpad(padded: ByteArray): String {
        val len = ((padded[0].toInt() and 0xFF) shl 8) or (padded[1].toInt() and 0xFF)
        require(len in 1..65535 && len <= padded.size - 2) { "invalid padding" }
        require(calcPaddedLen(len) == padded.size - 2) { "invalid padding length" }
        return String(padded, 2, len, Charsets.UTF_8)
    }

    // --- Primitives ---

    private fun chacha20(key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray {
        val engine = ChaCha7539Engine()
        engine.init(true, ParametersWithIV(KeyParameter(key), nonce))
        val output = ByteArray(data.size)
        engine.processBytes(data, 0, data.size, output, 0)
        return output
    }

    private fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    /** HKDF-Extract (RFC 5869): PRK = HMAC-SHA256(salt, IKM) */
    fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray = hmacSha256(salt, ikm)

    /** HKDF-Expand (RFC 5869): OKM = T(1) || T(2) || ... */
    fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length <= 255 * 32)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val result = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter: Byte = 1
        while (offset < length) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(byteArrayOf(counter))
            t = mac.doFinal()
            val toCopy = minOf(t.size, length - offset)
            System.arraycopy(t, 0, result, offset, toCopy)
            offset += toCopy
            counter++
        }
        return result
    }
}
