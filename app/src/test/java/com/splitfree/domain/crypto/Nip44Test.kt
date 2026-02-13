package com.splitfree.domain.crypto

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.security.MessageDigest

/**
 * NIP-44 v2 tests using official test vectors from
 * https://github.com/paulmillr/nip44/blob/main/nip44.vectors.json
 */
class Nip44Test {
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
}
