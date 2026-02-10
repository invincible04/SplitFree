package com.splitfree.domain.crypto

import org.junit.Assert.*
import org.junit.Test
import fr.acinq.secp256k1.Secp256k1
import java.security.MessageDigest

/**
 * NIP-01 tests: event ID computation, signing, verification, JSON serialization.
 * Includes BIP-340 Schnorr signature test vectors from bitcoin/bips.
 */
class Nip01Test {

    // Known test private key (32 bytes hex)
    private val privHex = "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a"
    private val privKey = privHex.hexToBytes()

    // --- BIP-340 Schnorr Signature Test Vectors ---
    // These validate that our signing/verification uses the correct BIP-340 algorithm.

    @Test
    fun `BIP-340 signing vectors - verify known signatures`() {
        // Official BIP-340 test vectors (verification only — vectors 4-14 have no secret key)
        val verifyVectors = listOf(
            // index, pubkey, message (32-byte hex), signature, expected result
            Triple(
                "F9308A019258C31049344F85F89D5229B531C845836F99B08601F113BCE036F9",
                "0000000000000000000000000000000000000000000000000000000000000000",
                "E907831F80848D1069A5371B402410364BDF1C5F8307B0084C55F1CE2DCA821525F66A4A85EA8B71E482A74F382D2CE5EBEEE8FDB2172F477DF4900D310536C0"
            ),
            Triple(
                "DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659",
                "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
                "6896BD60EEAE296DB48A229FF71DFE071BDE413E6D43F917DC8DCF8C78DE33418906D11AC976ABCCB20B091292BFF4EA897EFCB639EA871CFA95F6DE339E4B0A"
            ),
            Triple(
                "DD308AFEC5777E13121FA72B9CC1B7CC0139715309B086C960E18FD969774EB8",
                "7E2D58D8B3BCDF1ABADEC7829054F90DDA9805AAB56C77333024B9D0A508B75C",
                "5831AAEED7B44BB74E5EAB94BA9D4294C49BCF2A60728D8B4C200F50DD313C1BAB745879A5AD954A72C45A91C3A51D3C7ADEA98D82F8481E0E1E03674A6F3FB7"
            ),
        )

        for ((i, vec) in verifyVectors.withIndex()) {
            val (pubHex, msgHex, sigHex) = vec
            val result = Secp256k1.verifySchnorr(
                sigHex.lowercase().hexToBytes(),
                msgHex.lowercase().hexToBytes(),
                pubHex.lowercase().hexToBytes()
            )
            assertTrue("BIP-340 verify vector $i should pass", result)
        }
    }

    @Test
    fun `BIP-340 invalid signature vectors - must reject`() {
        // Vectors 5-14: known invalid signatures
        val invalidVectors = listOf(
            // pubkey, message, signature, comment
            arrayOf(
                "DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659",
                "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
                "FFF97BD5755EEEA420453A14355235D382F6472F8568A18B2F057A14602975563CC27944640AC607CD107AE10923D9EF7A73C643E166BE5EBEAFA34B1AC553E2",
                "has_even_y(R) is false"
            ),
            arrayOf(
                "DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659",
                "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
                "1FA62E331EDBC21C394792D2AB1100A7B432B013DF3F6FF4F99FCB33E0E1515F28890B3EDB6E7189B630448B515CE4F8622A954CFE545735AAEA5134FCCDB2BD",
                "negated message"
            ),
            arrayOf(
                "DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659",
                "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
                "6CFF5C3BA86C69EA4B7376F31A9BCB4F74C1976089B2D9963DA2E5543E177769961764B3AA9B2FFCB6EF947B6887A226E8D7C93E00C5ED0C1834FF0D0C2E6DA6",
                "negated s value"
            ),
        )

        for ((i, vec) in invalidVectors.withIndex()) {
            val result = try {
                Secp256k1.verifySchnorr(
                    vec[2].lowercase().hexToBytes(),
                    vec[1].lowercase().hexToBytes(),
                    vec[0].lowercase().hexToBytes()
                )
            } catch (_: Exception) { false }
            assertFalse("BIP-340 invalid vector $i (${vec[3]}) should fail", result)
        }
    }

    @Test
    fun `BIP-340 sign and verify with known key`() {
        // Vector 0: secret=03, pubkey=F930..., msg=0000..., aux=0000...
        val sec = "0000000000000000000000000000000000000000000000000000000000000003".hexToBytes()
        val msg = "0000000000000000000000000000000000000000000000000000000000000000".hexToBytes()
        val aux = "0000000000000000000000000000000000000000000000000000000000000000".hexToBytes()
        val expectedSig = "E907831F80848D1069A5371B402410364BDF1C5F8307B0084C55F1CE2DCA821525F66A4A85EA8B71E482A74F382D2CE5EBEEE8FDB2172F477DF4900D310536C0"

        val sig = Secp256k1.signSchnorr(msg, sec, aux)
        assertEquals(expectedSig.lowercase(), sig.toHex())
    }

    // --- NIP-01 Event ID Computation ---

    @Test
    fun `event ID is deterministic SHA-256 of canonical serialization`() {
        // Construct a known event and verify the ID is computed correctly
        val pubHex = NostrEvent.pubkeyFromPrivkey(privKey)
        val event = NostrEvent(
            pubkey = pubHex,
            createdAt = 1234567890L,
            kind = 1,
            tags = listOf(listOf("e", "abc123")),
            content = "Hello Nostr"
        )
        val id = event.computeId()
        assertEquals(32, id.size)

        // Manually compute expected: SHA-256([0,"<pubkey>",1234567890,1,[["e","abc123"]],"Hello Nostr"])
        val serialized = """[0,"$pubHex",1234567890,1,[["e","abc123"]],"Hello Nostr"]"""
        val expectedId = MessageDigest.getInstance("SHA-256")
            .digest(serialized.toByteArray(Charsets.UTF_8))
        assertArrayEquals("Event ID must match manual SHA-256", expectedId, id)
    }

    @Test
    fun `event ID changes with any field modification`() {
        val pubHex = NostrEvent.pubkeyFromPrivkey(privKey)
        val base = NostrEvent(pubkey = pubHex, createdAt = 1L, kind = 1, content = "test")
        val baseId = base.computeId()

        // Each modification must produce a different ID
        assertFalse(baseId.contentEquals(base.copy(content = "other").computeId()))
        assertFalse(baseId.contentEquals(base.copy(createdAt = 2L).computeId()))
        assertFalse(baseId.contentEquals(base.copy(kind = 2).computeId()))
        assertFalse(baseId.contentEquals(base.copy(tags = listOf(listOf("t", "x"))).computeId()))
    }

    // --- NIP-01 Signing ---

    @Test
    fun `pubkey derivation from private key`() {
        val pub = NostrEvent.pubkeyFromPrivkey(privKey)
        assertEquals(64, pub.length)
        pub.hexToBytes() // should not throw
    }

    @Test
    fun `sign and verify round-trip`() {
        val pubHex = NostrEvent.pubkeyFromPrivkey(privKey)
        val event = NostrEvent(
            pubkey = pubHex,
            createdAt = 1234567890L,
            kind = 30078,
            tags = listOf(listOf("d", "test-group"), listOf("t", "expense")),
            content = "encrypted-content-here"
        )

        val signed = event.sign(privKey)
        assertTrue(signed.id.isNotEmpty())
        assertTrue(signed.sig.isNotEmpty())
        assertEquals(64, signed.id.length)
        assertEquals(128, signed.sig.length)
        assertTrue("Signature verification failed", signed.verify())
    }

    @Test
    fun `verify rejects tampered event`() {
        val pubHex = NostrEvent.pubkeyFromPrivkey(privKey)
        val signed = NostrEvent(
            pubkey = pubHex, createdAt = 1234567890L, kind = 1, content = "original"
        ).sign(privKey)

        assertFalse("Tampered content", signed.copy(content = "tampered").verify())
        assertFalse("Wrong pubkey", signed.copy(
            pubkey = "0000000000000000000000000000000000000000000000000000000000000001"
        ).verify())
    }

    @Test
    fun `verify rejects empty fields`() {
        assertFalse(NostrEvent(pubkey = "", createdAt = 0, kind = 1, content = "").verify())
    }

    // --- NIP-01 JSON Serialization ---

    @Test
    fun `JSON serialization round-trip preserves all fields`() {
        val pubHex = NostrEvent.pubkeyFromPrivkey(privKey)
        val signed = NostrEvent(
            pubkey = pubHex,
            createdAt = 1700000000L,
            kind = 30078,
            tags = listOf(listOf("d", "group1"), listOf("t", "expense")),
            content = "test content with \"quotes\" and \\backslash"
        ).sign(privKey)

        val json = signed.toJson()
        val parsed = NostrEvent.fromJson(json)!!

        assertEquals(signed.id, parsed.id)
        assertEquals(signed.pubkey, parsed.pubkey)
        assertEquals(signed.createdAt, parsed.createdAt)
        assertEquals(signed.kind, parsed.kind)
        assertEquals(signed.tags, parsed.tags)
        assertEquals(signed.content, parsed.content)
        assertEquals(signed.sig, parsed.sig)
        assertTrue("Parsed event should verify", parsed.verify())
    }

    @Test
    fun `JSON escaping per NIP-01 spec`() {
        val pubHex = NostrEvent.pubkeyFromPrivkey(privKey)
        val event = NostrEvent(
            pubkey = pubHex, createdAt = 1L, kind = 1,
            content = "line1\nline2\ttab\r\n\"quoted\"\\escaped\b\u000C"
        ).sign(privKey)

        val json = event.toJson()
        assertTrue(json.contains("\\n"))
        assertTrue(json.contains("\\t"))
        assertTrue(json.contains("\\r"))
        assertTrue(json.contains("\\\""))
        assertTrue(json.contains("\\\\"))
        assertTrue(json.contains("\\b"))
        assertTrue(json.contains("\\f"))

        assertEquals(event.content, NostrEvent.fromJson(json)!!.content)
    }

    @Test
    fun `fromJson returns null for invalid JSON`() {
        assertNull(NostrEvent.fromJson("not json"))
        assertNull(NostrEvent.fromJson(""))
        assertNull(NostrEvent.fromJson("[]"))
    }

    @Test
    fun `hex conversion round-trip`() {
        val bytes = byteArrayOf(0x00, 0x01, 0x0F, 0x10, 0xFF.toByte(), 0xAB.toByte())
        assertEquals("00010f10ffab", bytes.toHex())
        assertArrayEquals(bytes, "00010f10ffab".hexToBytes())
    }

    @Test
    fun `two different keys produce different signatures`() {
        val priv2 = "0000000000000000000000000000000000000000000000000000000000000001".hexToBytes()
        val pub1 = NostrEvent.pubkeyFromPrivkey(privKey)
        val pub2 = NostrEvent.pubkeyFromPrivkey(priv2)
        assertNotEquals(pub1, pub2)

        val e1 = NostrEvent(pubkey = pub1, createdAt = 1L, kind = 1, content = "test").sign(privKey)
        val e2 = NostrEvent(pubkey = pub2, createdAt = 1L, kind = 1, content = "test").sign(priv2)
        assertTrue(e1.verify())
        assertTrue(e2.verify())
        assertNotEquals(e1.sig, e2.sig)
    }

    // --- Cross-verification: sign with our code, verify with raw secp256k1 ---

    @Test
    fun `signed event verifiable with raw secp256k1 API`() {
        val pubHex = NostrEvent.pubkeyFromPrivkey(privKey)
        val signed = NostrEvent(
            pubkey = pubHex, createdAt = 1L, kind = 1, content = "cross-verify"
        ).sign(privKey)

        // Verify using raw secp256k1 API directly (not our NostrEvent.verify())
        val idBytes = signed.id.hexToBytes()
        val sigBytes = signed.sig.hexToBytes()
        val pubBytes = signed.pubkey.hexToBytes()
        assertTrue(
            "Raw secp256k1 verification must pass",
            Secp256k1.verifySchnorr(sigBytes, idBytes, pubBytes)
        )
    }

    // --- Pubkey derivation cross-check against BIP-340 vectors ---

    @Test
    fun `pubkey derivation matches BIP-340 test vectors`() {
        // BIP-340 vector 0: secret=0x03 → pubkey=F9308A...
        val sec0 = "0000000000000000000000000000000000000000000000000000000000000003".hexToBytes()
        assertEquals(
            "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9",
            NostrEvent.pubkeyFromPrivkey(sec0)
        )

        // BIP-340 vector 1: secret=B7E151... → pubkey=DFF1D7...
        val sec1 = "B7E151628AED2A6ABF7158809CF4F3C762E7160F38B4DA56A784D9045190CFEF".lowercase().hexToBytes()
        assertEquals(
            "dff1d77f2a671c5f36183726db2341be58feae1da2deced843240f7b502ba659",
            NostrEvent.pubkeyFromPrivkey(sec1)
        )

        // BIP-340 vector 2: secret=C90FDA... → pubkey=DD308A...
        val sec2 = "C90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B14E5C9".lowercase().hexToBytes()
        assertEquals(
            "dd308afec5777e13121fa72b9cc1b7cc0139715309b086c960e18fd969774eb8",
            NostrEvent.pubkeyFromPrivkey(sec2)
        )

        // BIP-340 vector 3: secret=0B432B... → pubkey=25D1DF...
        val sec3 = "0B432B2677937381AEF05BB02A66ECD012773062CF3FA2549E44F58ED2401710".lowercase().hexToBytes()
        assertEquals(
            "25d1dff95105f5253c4022f628a996ad3a0d95fbf21d468a1b33f8c160d8f517",
            NostrEvent.pubkeyFromPrivkey(sec3)
        )
    }

    // --- NIP-01 Event ID: deterministic cross-check ---

    @Test
    fun `event ID matches independent SHA-256 computation`() {
        // Use BIP-340 vector 0 key so pubkey is externally verifiable
        val sec = "0000000000000000000000000000000000000000000000000000000000000003".hexToBytes()
        val pub = "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9"

        val event = NostrEvent(
            pubkey = pub,
            createdAt = 1700000000L,
            kind = 1,
            tags = listOf(listOf("p", "abcd1234")),
            content = "Hello, world!"
        )

        // Build the canonical serialization manually per NIP-01 spec
        val canonical = """[0,"$pub",1700000000,1,[["p","abcd1234"]],"Hello, world!"]"""
        val expectedId = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8)).toHex()

        assertEquals(expectedId, event.computeId().toHex())
    }
}
