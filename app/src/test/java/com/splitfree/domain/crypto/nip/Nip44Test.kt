package com.splitfree.domain.crypto.nip

import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.nip.Bip39
import com.splitfree.domain.crypto.nip.Nip44
import com.splitfree.domain.crypto.nip.Nip59
import com.splitfree.domain.util.hexToBytes
import com.splitfree.domain.util.toHex
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test

/**
 * NIP-44 v2 tests using official test vectors from
 * https://github.com/paulmillr/nip44/blob/main/nip44.vectors.json
 */
class Nip44Test {
    private val privA = "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a".hexToBytes()
    private val privB = ("a".repeat(63) + "1").hexToBytes()
    private val pubB = NostrEvent.pubkeyFromPrivkey(privB).hexToBytes()
    companion object {
        private lateinit var vectors: JsonObject

        @BeforeClass
        @JvmStatic
        fun loadVectors() {
            val json =
                Nip44Test::class.java
                    .getResourceAsStream("/nip44.vectors.json")!!
                    .bufferedReader()
                    .readText()
            vectors = Json.parseToJsonElement(json).jsonObject["v2"]!!.jsonObject
        }
    }

    // --- Conversation Key Derivation (35 vectors) ---

    @Test
    fun `conversation key derivation matches all test vectors`() {
        val cases = vectors["valid"]!!.jsonObject["get_conversation_key"]!!.jsonArray
        for ((i, case) in cases.withIndex()) {
            val obj = case.jsonObject
            val sec1 = obj["sec1"]!!.jsonPrimitive.content
            val pub2 = obj["pub2"]!!.jsonPrimitive.content
            val expected = obj["conversation_key"]!!.jsonPrimitive.content

            val actual = Nip44.getConversationKey(sec1.hexToBytes(), pub2.hexToBytes()).toHex()
            assertEquals("conversation key mismatch at vector $i", expected, actual)
        }
    }

    // --- Message Keys (HKDF-Expand) ---

    @Test
    fun `message key derivation matches test vectors`() {
        val mk = vectors["valid"]!!.jsonObject["get_message_keys"]!!.jsonObject
        val convKey = mk["conversation_key"]!!.jsonPrimitive.content.hexToBytes()
        val keys = mk["keys"]!!.jsonArray

        for ((i, entry) in keys.withIndex()) {
            val obj = entry.jsonObject
            val nonce = obj["nonce"]!!.jsonPrimitive.content.hexToBytes()
            val expectedChachaKey = obj["chacha_key"]!!.jsonPrimitive.content
            val expectedChachaNonce = obj["chacha_nonce"]!!.jsonPrimitive.content
            val expectedHmacKey = obj["hmac_key"]!!.jsonPrimitive.content

            val expanded = Nip44.hkdfExpand(convKey, nonce, 76)
            val chachaKey = expanded.copyOfRange(0, 32).toHex()
            val chaChaNonce = expanded.copyOfRange(32, 44).toHex()
            val hmacKey = expanded.copyOfRange(44, 76).toHex()

            assertEquals("chacha_key mismatch at $i", expectedChachaKey, chachaKey)
            assertEquals("chacha_nonce mismatch at $i", expectedChachaNonce, chaChaNonce)
            assertEquals("hmac_key mismatch at $i", expectedHmacKey, hmacKey)
        }
    }

    // --- Padding ---

    @Test
    fun `padding length matches all test vectors`() {
        val cases = vectors["valid"]!!.jsonObject["calc_padded_len"]!!.jsonArray
        for (case in cases) {
            val arr = case.jsonArray
            val input = arr[0].jsonPrimitive.int
            val expected = arr[1].jsonPrimitive.int
            if (input > 65535) continue // app caps plaintext at 65535 bytes per NIP-44 encrypt limit
            assertEquals("padded len for $input", expected, Nip44.calcPaddedLen(input))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `calcPaddedLen rejects length above 65535`() {
        Nip44.calcPaddedLen(65536)
    }

    // --- Encrypt/Decrypt (10 vectors) ---

    @Test
    fun `encrypt matches all test vectors`() {
        val cases = vectors["valid"]!!.jsonObject["encrypt_decrypt"]!!.jsonArray
        for ((i, case) in cases.withIndex()) {
            val obj = case.jsonObject
            val sec1 = obj["sec1"]!!.jsonPrimitive.content
            val sec2 = obj["sec2"]!!.jsonPrimitive.content
            val convKey = obj["conversation_key"]!!.jsonPrimitive.content
            val nonce = obj["nonce"]!!.jsonPrimitive.content
            val plaintext = obj["plaintext"]!!.jsonPrimitive.content
            val expectedPayload = obj["payload"]!!.jsonPrimitive.content

            // Verify conversation key derivation
            val pub2 = NostrEvent.pubkeyFromPrivkey(sec2.hexToBytes())
            val derivedConvKey = Nip44.getConversationKey(sec1.hexToBytes(), pub2.hexToBytes())
            assertEquals("conv key mismatch at $i", convKey, derivedConvKey.toHex())

            // Verify encryption with known nonce
            val encrypted = Nip44.encrypt(plaintext, convKey.hexToBytes(), nonce.hexToBytes())
            assertEquals("encrypt mismatch at $i", expectedPayload, encrypted)

            // Verify decryption
            val decrypted = Nip44.decrypt(encrypted, convKey.hexToBytes())
            assertEquals("decrypt mismatch at $i", plaintext, decrypted)
        }
    }

    // --- Encrypt/Decrypt Long Messages (SHA-256 verified) ---

    @Test
    fun `encrypt long messages match SHA-256 test vectors`() {
        val cases = vectors["valid"]!!.jsonObject["encrypt_decrypt_long_msg"]!!.jsonArray
        for ((i, case) in cases.withIndex()) {
            val obj = case.jsonObject
            val convKey = obj["conversation_key"]!!.jsonPrimitive.content.hexToBytes()
            val nonce = obj["nonce"]!!.jsonPrimitive.content.hexToBytes()
            val pattern = obj["pattern"]!!.jsonPrimitive.content
            val repeat = obj["repeat"]!!.jsonPrimitive.int
            val expectedPtSha = obj["plaintext_sha256"]!!.jsonPrimitive.content
            val expectedPayloadSha = obj["payload_sha256"]!!.jsonPrimitive.content

            val plaintext = pattern.repeat(repeat)
            val ptSha = sha256Hex(plaintext.toByteArray())
            assertEquals("plaintext sha mismatch at $i", expectedPtSha, ptSha)

            val payload = Nip44.encrypt(plaintext, convKey, nonce)
            val payloadSha = sha256Hex(payload.toByteArray())
            assertEquals("payload sha mismatch at $i", expectedPayloadSha, payloadSha)

            val decrypted = Nip44.decrypt(payload, convKey)
            assertEquals("long msg decrypt mismatch at $i", plaintext, decrypted)
        }
    }

    // --- Invalid: encrypt message lengths ---

    @Test
    fun `reject invalid plaintext lengths`() {
        val lengths = vectors["invalid"]!!.jsonObject["encrypt_msg_lengths"]!!.jsonArray
        val dummyKey = ByteArray(32) { 1 }
        for (len in lengths) {
            val n = len.jsonPrimitive.int
            try {
                if (n == 0) {
                    Nip44.encrypt("", dummyKey)
                } else {
                    Nip44.encrypt("a".repeat(n), dummyKey)
                }
                fail("Should reject plaintext length $n")
            } catch (_: Exception) {
                // expected
            }
        }
    }

    // --- Invalid: conversation key derivation ---

    @Test
    fun `reject invalid conversation key inputs`() {
        val cases = vectors["invalid"]!!.jsonObject["get_conversation_key"]!!.jsonArray
        for (case in cases) {
            val obj = case.jsonObject
            val sec1 = obj["sec1"]!!.jsonPrimitive.content
            val pub2 = obj["pub2"]!!.jsonPrimitive.content
            val note = obj["note"]!!.jsonPrimitive.content
            try {
                Nip44.getConversationKey(sec1.hexToBytes(), pub2.hexToBytes())
                fail("Should reject: $note")
            } catch (_: Exception) {
                // expected
            }
        }
    }

    // --- Invalid: decrypt ---

    @Test
    fun `reject invalid decrypt payloads`() {
        val cases = vectors["invalid"]!!.jsonObject["decrypt"]!!.jsonArray
        for (case in cases) {
            val obj = case.jsonObject
            val convKey = obj["conversation_key"]!!.jsonPrimitive.content.hexToBytes()
            val payload = obj["payload"]!!.jsonPrimitive.content
            val note = obj["note"]!!.jsonPrimitive.content
            try {
                Nip44.decrypt(payload, convKey)
                fail("Should reject decrypt: $note")
            } catch (_: Exception) {
                // expected
            }
        }
    }

    // --- Round-trip ---

    @Test
    fun `encrypt then decrypt round-trip`() {
        val privA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".hexToBytes()
        val privB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb".hexToBytes()
        val pubB = NostrEvent.pubkeyFromPrivkey(privB).hexToBytes()
        val pubA = NostrEvent.pubkeyFromPrivkey(privA).hexToBytes()

        val convKeyAB = Nip44.getConversationKey(privA, pubB)
        val convKeyBA = Nip44.getConversationKey(privB, pubA)
        assertArrayEquals("conversation key must be symmetric", convKeyAB, convKeyBA)

        val msg = "Hello, NIP-44!"
        val encrypted = Nip44.encrypt(msg, convKeyAB)
        val decrypted = Nip44.decrypt(encrypted, convKeyBA)
        assertEquals(msg, decrypted)
    }

    private fun sha256Hex(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).toHex()

    // --- Edge case tests ---
    @Test
    fun `conversation key is symmetric`() {
        val pubA = NostrEvent.pubkeyFromPrivkey(privA).hexToBytes()
        val keyAB = Nip44.getConversationKey(privA, pubB)
        val keyBA = Nip44.getConversationKey(privB, pubA)
        assertArrayEquals("conv(a,B) must equal conv(b,A)", keyAB, keyBA)
    }

    @Test
    fun `encrypt-decrypt with 1-byte plaintext (minimum)`() {
        val convKey = Nip44.getConversationKey(privA, pubB)
        val encrypted = Nip44.encrypt("a", convKey)
        assertEquals("a", Nip44.decrypt(encrypted, convKey))
    }

    @Test
    fun `encrypt-decrypt with max-length plaintext`() {
        val convKey = Nip44.getConversationKey(privA, pubB)
        val plaintext = "x".repeat(65535)
        val encrypted = Nip44.encrypt(plaintext, convKey)
        assertEquals(plaintext, Nip44.decrypt(encrypted, convKey))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encrypt rejects empty plaintext`() {
        val convKey = Nip44.getConversationKey(privA, pubB)
        Nip44.encrypt("", convKey)
    }

    @Test
    fun `encrypt-decrypt with all JSON special characters`() {
        val convKey = Nip44.getConversationKey(privA, pubB)
        val text = "\"\\\n\r\t\b\u000C"
        assertEquals(text, Nip44.decrypt(Nip44.encrypt(text, convKey), convKey))
    }

    @Test
    fun `encrypt-decrypt with emoji and multibyte UTF-8`() {
        val convKey = Nip44.getConversationKey(privA, pubB)
        val text = "🎉🍕💰 café ₹500 日本語"
        assertEquals(text, Nip44.decrypt(Nip44.encrypt(text, convKey), convKey))
    }

    @Test
    fun `different nonces produce different ciphertext`() {
        val convKey = Nip44.getConversationKey(privA, pubB)
        val e1 = Nip44.encrypt("same", convKey)
        val e2 = Nip44.encrypt("same", convKey)
        assertNotEquals(e1, e2)
    }

    @Test(expected = Exception::class)
    fun `decrypt rejects tampered ciphertext`() {
        val convKey = Nip44.getConversationKey(privA, pubB)
        val encrypted = Nip44.encrypt("hello", convKey)
        // Flip a character in the middle of the base64
        val tampered = encrypted.substring(0, 50) + "X" + encrypted.substring(51)
        Nip44.decrypt(tampered, convKey)
    }

    @Test(expected = Exception::class)
    fun `decrypt rejects wrong conversation key`() {
        val convKey = Nip44.getConversationKey(privA, pubB)
        val wrongKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val encrypted = Nip44.encrypt("secret", convKey)
        Nip44.decrypt(encrypted, wrongKey)
    }

    @Test(expected = Exception::class)
    fun `decrypt rejects version 0x01`() {
        val convKey = Nip44.getConversationKey(privA, pubB)
        val encrypted = Nip44.encrypt("test", convKey)
        val decoded =
            java.util.Base64
                .getDecoder()
                .decode(encrypted)
        decoded[0] = 0x01 // change version
        val reencoded =
            java.util.Base64
                .getEncoder()
                .encodeToString(decoded)
        Nip44.decrypt(reencoded, convKey)
    }

    @Test(expected = Exception::class)
    fun `decrypt rejects payload starting with hash`() {
        val convKey = Nip44.getConversationKey(privA, pubB)
        Nip44.decrypt("#future-version-payload", convKey)
    }

    // --- Padding ---

    @Test
    fun `calcPaddedLen matches spec for small values`() {
        assertEquals(32, Nip44.calcPaddedLen(1))
        assertEquals(32, Nip44.calcPaddedLen(32))
        assertEquals(64, Nip44.calcPaddedLen(33))
        assertEquals(64, Nip44.calcPaddedLen(64))
        assertEquals(96, Nip44.calcPaddedLen(65))
    }

    @Test
    fun `calcPaddedLen is always at least 32`() {
        for (i in 1..32) {
            assertEquals("Len $i should pad to 32", 32, Nip44.calcPaddedLen(i))
        }
    }

    @Test
    fun `calcPaddedLen is monotonically non-decreasing`() {
        var prev = 0
        for (i in 1..1000) {
            val padded = Nip44.calcPaddedLen(i)
            assertTrue("Padded length must not decrease", padded >= prev)
            assertTrue("Padded length must be >= input", padded >= i)
            prev = padded
        }
    }

    // --- HKDF ---

    @Test
    fun `hkdfExtract produces 32-byte output`() {
        val result = Nip44.hkdfExtract("salt".toByteArray(), "ikm".toByteArray())
        assertEquals(32, result.size)
    }

    @Test
    fun `hkdfExpand produces requested length`() {
        val prk = Nip44.hkdfExtract("salt".toByteArray(), "ikm".toByteArray())
        assertEquals(76, Nip44.hkdfExpand(prk, "info".toByteArray(), 76).size)
        assertEquals(32, Nip44.hkdfExpand(prk, "info".toByteArray(), 32).size)
        assertEquals(1, Nip44.hkdfExpand(prk, "info".toByteArray(), 1).size)
    }

    @Test
    fun `hkdfExpand is deterministic`() {
        val prk = Nip44.hkdfExtract("salt".toByteArray(), "ikm".toByteArray())
        val a = Nip44.hkdfExpand(prk, "info".toByteArray(), 76)
        val b = Nip44.hkdfExpand(prk, "info".toByteArray(), 76)
        assertArrayEquals(a, b)
    }

    @Test
    fun `getConversationKey accepts 33-byte compressed pubkey`() {
        val pub33 = byteArrayOf(0x02) + pubB
        val key32 = Nip44.getConversationKey(privA, pubB)
        val key33 = Nip44.getConversationKey(privA, pub33)
        assertArrayEquals(key32, key33)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decrypt rejects too-short decoded payload`() {
        val convKey = Nip44.getConversationKey(privA, pubB)
        // Create a valid base64 that decodes to < 99 bytes but passes length check
        val tooShort =
            java.util.Base64
                .getEncoder()
                .encodeToString(ByteArray(98) { 0x02 })
        Nip44.decrypt(tooShort, convKey)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decrypt rejects empty payload`() {
        val convKey = Nip44.getConversationKey(privA, pubB)
        Nip44.decrypt("", convKey)
    }
}
