package com.splitfree.domain.crypto.nip

import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.nip.Bip39
import com.splitfree.domain.crypto.nip.Nip44
import com.splitfree.domain.crypto.nip.Nip59
import com.splitfree.domain.util.hexToBytes
import com.splitfree.domain.util.toHex
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NIP-59 Gift Wrap tests: wrap/unwrap round-trip, layer verification.
 */
class Nip59Test {
    private val senderPriv = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".hexToBytes()
    private val recipientPriv = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb".hexToBytes()
    private val senderPub = NostrEvent.pubkeyFromPrivkey(senderPriv)
    private val recipientPub = NostrEvent.pubkeyFromPrivkey(recipientPriv).hexToBytes()

    private fun makeRumor() = NostrEvent(
        pubkey = senderPub,
        createdAt = 1700000000L,
        kind = 30078,
        tags = listOf(listOf("d", "test-group"), listOf("t", "expense")),
        content = "encrypted-expense-data",
        sig = "" // rumor must be unsigned
    )

    @Test
    fun `gift wrap produces kind 1059 event`() {
        val wrapped = Nip59.giftWrap(makeRumor(), senderPriv, recipientPub)
        assertEquals(1059, wrapped.kind)
        assertTrue(wrapped.id.isNotEmpty())
        assertTrue(wrapped.sig.isNotEmpty())
        assertTrue(wrapped.verify())
    }

    @Test
    fun `gift wrap has p tag with recipient`() {
        val wrapped = Nip59.giftWrap(makeRumor(), senderPriv, recipientPub)
        val pTag = wrapped.tags.find { it[0] == "p" }
        assertNotNull("Must have p tag", pTag)
        assertEquals(recipientPub.toHex(), pTag!![1])
    }

    @Test
    fun `gift wrap pubkey is ephemeral, not sender`() {
        val wrapped = Nip59.giftWrap(makeRumor(), senderPriv, recipientPub)
        assertNotEquals("Pubkey must be ephemeral", senderPub, wrapped.pubkey)
    }

    @Test
    fun `unwrap recovers original rumor`() {
        val rumor = makeRumor()
        val wrapped = Nip59.giftWrap(rumor, senderPriv, recipientPub)
        val result = Nip59.unwrap(wrapped, recipientPriv)

        assertNotNull("Unwrap should succeed", result)
        val (recovered, senderPubHex) = result!!

        assertEquals(senderPub, senderPubHex)
        assertEquals(rumor.kind, recovered.kind)
        assertEquals(rumor.content, recovered.content)
        assertEquals(rumor.tags, recovered.tags)
        assertEquals(rumor.createdAt, recovered.createdAt)
        assertEquals("", recovered.sig) // rumor is unsigned
    }

    @Test
    fun `unwrap returns the seal signature that authenticates the rumor`() {
        val wrapped = Nip59.giftWrap(makeRumor(), senderPriv, recipientPub)

        // Independently peel the outer layer to get at the seal the sender signed.
        val wrapConvKey = Nip44.getConversationKey(recipientPriv, wrapped.pubkey.hexToBytes())
        val seal = NostrEvent.fromJson(Nip44.decrypt(wrapped.content, wrapConvKey))!!
        assertTrue(seal.verify())

        val result = Nip59.unwrap(wrapped, recipientPriv)!!
        assertEquals(seal.sig, result.sealSig)
        assertEquals(128, result.sealSig.length)
        assertEquals(seal.pubkey, result.senderPubkey)
        // The seal signature is over the seal, not the rumor: the rumor itself stays unsigned.
        assertEquals("", result.rumor.sig)
    }

    @Test
    fun `unwrap fails with wrong recipient key`() {
        val wrapped = Nip59.giftWrap(makeRumor(), senderPriv, recipientPub)
        val wrongKey = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc".hexToBytes()
        val result = Nip59.unwrap(wrapped, wrongKey)
        assertNull("Unwrap with wrong key should fail", result)
    }

    @Test
    fun `unwrap rejects non-gift-wrap events`() {
        val event =
            NostrEvent(
                pubkey = senderPub,
                createdAt = 1L,
                kind = 1, // not 1059
                content = "not a gift wrap"
            ).sign(senderPriv)
        assertNull(Nip59.unwrap(event, recipientPriv))
    }

    @Test
    fun `unwrap rejects tampered gift wrap`() {
        val wrapped = Nip59.giftWrap(makeRumor(), senderPriv, recipientPub)
        val tampered = wrapped.copy(content = "tampered")
        assertNull("Tampered gift wrap should fail", Nip59.unwrap(tampered, recipientPriv))
    }

    @Test
    fun `each wrap produces different ephemeral keys`() {
        val w1 = Nip59.giftWrap(makeRumor(), senderPriv, recipientPub)
        val w2 = Nip59.giftWrap(makeRumor(), senderPriv, recipientPub)
        assertNotEquals("Ephemeral keys must differ", w1.pubkey, w2.pubkey)
        assertNotEquals("Ciphertext must differ", w1.content, w2.content)
    }

    @Test
    fun `seal layer has kind 13 and empty tags`() {
        // We can verify this indirectly: unwrap and check the seal was valid
        // (the unwrap function verifies seal.kind == 13 internally)
        val wrapped = Nip59.giftWrap(makeRumor(), senderPriv, recipientPub)
        val result = Nip59.unwrap(wrapped, recipientPriv)
        assertNotNull("Valid seal should unwrap", result)
    }

    // --- NIP-59 Structural Validation ---

    @Test
    fun `gift wrap outer layer structure per spec`() {
        val wrapped = Nip59.giftWrap(makeRumor(), senderPriv, recipientPub)
        assertEquals(1059, wrapped.kind)
        assertTrue(wrapped.verify())
        val pTags = wrapped.tags.filter { it[0] == "p" }
        assertEquals(1, pTags.size)
        assertNotEquals(senderPub, wrapped.pubkey)
        val now = System.currentTimeMillis() / 1000
        assertTrue(wrapped.createdAt <= now)
        assertTrue(wrapped.createdAt > now - 3 * 86400)
    }

    @Test
    fun `seal layer decrypted from gift wrap has correct structure`() {
        val wrapped = Nip59.giftWrap(makeRumor(), senderPriv, recipientPub)
        val wrapConvKey = Nip44.getConversationKey(recipientPriv, wrapped.pubkey.hexToBytes())
        val sealJson = Nip44.decrypt(wrapped.content, wrapConvKey)
        val seal = NostrEvent.fromJson(sealJson)!!

        assertEquals(13, seal.kind)
        assertTrue("Seal tags must be empty", seal.tags.isEmpty())
        assertEquals(senderPub, seal.pubkey)
        assertTrue("Seal must have valid signature", seal.verify())
    }

    @Test
    fun `rumor inside seal is unsigned`() {
        val wrapped = Nip59.giftWrap(makeRumor(), senderPriv, recipientPub)
        val (rumor, _) = Nip59.unwrap(wrapped, recipientPriv)!!
        assertEquals("", rumor.sig)
        assertTrue(rumor.id.isNotEmpty())
        assertEquals(64, rumor.id.length)
    }

    @Test
    fun `gift wrap uses g tag when rumor has group tag`() {
        val rumorWithG =
            NostrEvent(
                pubkey = senderPub,
                createdAt = 1700000000L,
                kind = 30078,
                tags = listOf(listOf("g", "group-123"), listOf("t", "expense")),
                content = "data",
                sig = ""
            )
        val wrapped = Nip59.giftWrap(rumorWithG, senderPriv, recipientPub)
        val gTag = wrapped.tags.find { it[0] == "g" }
        assertNotNull("Must have g tag", gTag)
        assertEquals("group-123", gTag!![1])
        assertNotNull("Must have p tag for relay routing", wrapped.tags.find { it[0] == "p" })
    }

    @Test
    fun `unwrap rejects mismatched rumor and seal pubkey`() {
        // Create a valid gift wrap but tamper the rumor's pubkey inside the seal
        val otherPriv = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd".hexToBytes()
        val otherPub = NostrEvent.pubkeyFromPrivkey(otherPriv)

        // Build a rumor with a DIFFERENT pubkey than the seal signer
        val rumor =
            NostrEvent(
                pubkey = otherPub,
                createdAt = 1700000000L,
                kind = 30078,
                tags = listOf(listOf("d", "test")),
                content = "data",
                sig = ""
            )
        // Seal is signed by senderPriv, but rumor.pubkey is otherPub → mismatch
        val sealConvKey = Nip44.getConversationKey(senderPriv, recipientPub)
        val sealContent = Nip44.encrypt(rumor.toJson(), sealConvKey)
        val seal =
            NostrEvent(
                pubkey = senderPub,
                createdAt = 1L,
                kind = 13,
                tags = emptyList(),
                content = sealContent
            ).sign(senderPriv)

        val ephPriv = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee".hexToBytes()
        val ephPub = NostrEvent.pubkeyFromPrivkey(ephPriv)
        val wrapConvKey = Nip44.getConversationKey(ephPriv, recipientPub)
        val wrapContent = Nip44.encrypt(seal.toJson(), wrapConvKey)
        val wrap =
            NostrEvent(
                pubkey = ephPub,
                createdAt = 1L,
                kind = 1059,
                tags = listOf(listOf("p", recipientPub.toHex())),
                content = wrapContent
            ).sign(ephPriv)

        assertNull("Should reject mismatched pubkeys", Nip59.unwrap(wrap, recipientPriv))
    }

    /** Build a seal + gift wrap around an arbitrary (possibly malformed) rumor, signed by [senderPriv]. */
    private fun wrapRawRumor(rumor: NostrEvent): NostrEvent {
        val sealConvKey = Nip44.getConversationKey(senderPriv, recipientPub)
        val sealContent = Nip44.encrypt(rumor.toJson(), sealConvKey)
        val seal =
            NostrEvent(
                pubkey = senderPub,
                createdAt = 1L,
                kind = 13,
                tags = emptyList(),
                content = sealContent
            ).sign(senderPriv)

        val ephPriv = randomKey()
        val ephPub = NostrEvent.pubkeyFromPrivkey(ephPriv)
        val wrapConvKey = Nip44.getConversationKey(ephPriv, recipientPub)
        val wrapContent = Nip44.encrypt(seal.toJson(), wrapConvKey)
        return NostrEvent(
            pubkey = ephPub,
            createdAt = 1L,
            kind = 1059,
            tags = listOf(listOf("p", recipientPub.toHex())),
            content = wrapContent
        ).sign(ephPriv)
    }

    @Test
    fun `unwrap rejects rumor with tampered id`() {
        val genuine = makeRumor()
        val goodId = genuine.computeId().toHex()
        val forgedId = "f".repeat(64)
        assertNotEquals(goodId, forgedId)

        // Same content and sender, but the rumor claims an id it did not derive from its fields.
        val wrap = wrapRawRumor(genuine.copy(id = forgedId))
        assertNull("Rumor id must be self-consistent", Nip59.unwrap(wrap, recipientPriv))
    }

    @Test
    fun `unwrap rejects rumor whose content was altered after id computation`() {
        val genuine = makeRumor()
        val rumorWithId = genuine.copy(id = genuine.computeId().toHex())
        // Attacker keeps the (valid-looking) id but swaps the content → id no longer matches.
        val altered = rumorWithId.copy(content = "different-content")
        assertNull("Altered rumor must be rejected", Nip59.unwrap(wrapRawRumor(altered), recipientPriv))
    }

    @Test
    fun `unwrap rejects rumor with empty id`() {
        val wrap = wrapRawRumor(makeRumor().copy(id = ""))
        assertNull("Rumor without id must be rejected", Nip59.unwrap(wrap, recipientPriv))
    }

    @Test
    fun `unwrap accepts hand-built rumor with correct id`() {
        val genuine = makeRumor()
        val rumorWithId = genuine.copy(id = genuine.computeId().toHex())
        val result = Nip59.unwrap(wrapRawRumor(rumorWithId), recipientPriv)
        assertNotNull("Self-consistent rumor must unwrap", result)
        assertEquals(rumorWithId.id, result!!.rumor.id)
    }

    @Test
    fun `unwrap is lenient toward a signed rumor as long as id is correct`() {
        val genuine = makeRumor()
        val signedRumor = genuine.sign(senderPriv) // sets a correct id plus a non-empty sig
        val result = Nip59.unwrap(wrapRawRumor(signedRumor), recipientPriv)
        assertNotNull("Signed rumor with consistent id should still unwrap", result)
    }

    @Test
    fun `unwrap rejects seal with wrong kind`() {
        val ephPriv = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc".hexToBytes()
        val ephPub = NostrEvent.pubkeyFromPrivkey(ephPriv)
        val fakeSeal =
            NostrEvent(
                pubkey = senderPub,
                createdAt = 1L,
                kind = 1,
                tags = emptyList(),
                content = "fake"
            ).sign(senderPriv)
        val wrapConvKey = Nip44.getConversationKey(ephPriv, recipientPub)
        val wrapContent = Nip44.encrypt(fakeSeal.toJson(), wrapConvKey)
        val fakeWrap =
            NostrEvent(
                pubkey = ephPub,
                createdAt = 1L,
                kind = 1059,
                tags = listOf(listOf("p", recipientPub.toHex())),
                content = wrapContent
            ).sign(ephPriv)
        assertNull("Should reject seal with wrong kind", Nip59.unwrap(fakeWrap, recipientPriv))
    }

    // --- NIP-59 Cross-Implementation Test (spec example) ---

    @Test
    fun `unwrap NIP-59 spec example gift wrap`() {
        // Keys from the NIP-59 spec example
        val recipientPrivKey = "e108399bd8424357a710b606ae0c13166d853d327e47a6e5e038197346bdbf45".hexToBytes()
        val senderPubKey = "611df01bfcf85c26ae65453b772d8f1dfd25c264621c0277e1fc1518686faef9"

        // The gift wrap event from the spec (kind 1059)
        val giftWrapJson =
            """{"id":"5c005f3ccf01950aa8d131203248544fb1e41a0d698e846bd419cec3890903ac",""" +
                """"pubkey":"18b1a75918f1f2c90c23da616bce317d36e348bcf5f7ba55e75949319210c87c",""" +
                """"created_at":1703021488,"kind":1059,""" +
                """"tags":[["p","166bf3765ebd1fc55decfe395beff2ea3b2a4e0a8946e7eb578512b555737c99"]],""" +
                """"content":"AhC3Qj/QsKJFWuf6xroiYip+2yK95qPwJjVvFujhzSguJWb/6TlPpBW0CGFwfufCs2Zyb0Jeu""" +
                """LmZhNlnqecAAalC4ZCugB+I9ViA5pxLyFfQjs1lcE6KdX3euCHBLAnE9GL/+IzdV9vZnfJH6atVjvBkNPN""" +
                """zxU+OLCHO/DAPmzmMVx0SR63frRTCz6Cuth40D+VzluKu1/Fg2Q1LSst65DE7o2efTtZ4Z9j15rQAOZfE9j""" +
                """wMCQZt27rBBK3yVwqVEriFpg2mHXc1DDwHhDADO8eiyOTWF1ghDds/DxhMcjkIi/o+FS3gG1dG7gJHu3Kk""" +
                """GK5UXpmgyFKt+421m5o++RMD/BylS3iazS1S93IzTLeGfMCk+7IKxuSCO06k1+DaasJJe8RE4/rmismUvwr""" +
                """Hu/HDutZWkvOAhd4z4khZo7bJLtiCzZCZ74lZcjOB4CYtuAX2ZGpc4I1iOKkvwTuQy9BWYpkzGg3ZoSWRD6""" +
                """ty7U+KN+fTTmIS4CelhBTT15QVqD02JxfLF7nA6sg3UlYgtiGw61oH68lSbx16P3vwSeQQpEB5JbhofW7t9""" +
                """TLZIbIW/ODnI4hpwj8didtk7IMBI3Ra3uUP7ya6vptkd9TwQkd/7cOFaSJmU+BIsLpOXbirJACMn+URoDXh""" +
                """uEtiO6xirNtrPN8jYqpwvMUm5lMMVzGT3kMMVNBqgbj8Ln8VmqouK0DR+gRyNb8fHT0BFPwsHxDskFk5yh""" +
                """e5c/2VUUoKCGe0kfCcX/EsHbJLUUtlHXmTqaOJpmQnW1tZ/siPwKRl6oEsIJWTUYxPQmrM2fUpYZCuAo/29""" +
                """lTLHiHMlTbarFOd6J/ybIbICy2gRRH/LFSryty3Cnf6aae+A9uizFBUdCwTwffc3vCBae802+R92OL78bbq""" +
                """HKPbSZOXNC+6ybqziezwG+OPWHx1Qk39RYaF0aFsM4uZWrFic97WwVrH5i+/Nsf/OtwWiuH0gV/SqvN1hn""" +
                """kxCTF/+XNn/laWKmS3e7wFzBsG8+qwqwmO9aVbDVMhOmeUXRMkxcj4QreQkHxLkCx97euZpC7xhvYnCHar""" +
                """HTDeD6nVK+xzbPNtzeGzNpYoiMqxZ9bBJwMaHnEoI944Vxoodf51cMIIwpTmmRvAzI1QgrfnOLOUS7uUjQ/""" +
                """IZ1Qa3lY08Nqm9MAGxZ2Ou6R0/Z5z30ha/Q71q6meAs3uHQcpSuRaQeV29IASmye2A2Nif+lmbhV7w8hjFY""" +
                """oaLCRsdchiVyNjOEM4VmxUhX4VEvw6KoCAZ/XvO2eBF/SyNU3Of4SO",""" +
                """"sig":"35fabdae4634eb630880a1896a886e40fd6ea8a60958e30b89b33a93e6235df7""" +
                """50097b04f9e13053764251b8bc5dd7e8e0794a3426a90b6bcc7e5ff660f54259"}"""
        val giftWrap = NostrEvent.fromJson(giftWrapJson)!!

        // Our implementation should be able to unwrap this
        val result = Nip59.unwrap(giftWrap, recipientPrivKey)
        assertNotNull("Must unwrap NIP-59 spec example", result)

        val (rumor, senderPub) = result!!
        assertEquals("Sender must match spec", senderPubKey, senderPub)
        assertEquals("Content must match spec", "Are you going to the party tonight?", rumor.content)
        assertEquals("Kind must be 1", 1, rumor.kind)
        assertEquals("Rumor must be unsigned", "", rumor.sig)
    }

    // --- Edge case tests ---
    @Test
    fun `wrap-unwrap preserves all rumor fields`() {
        val sender = randomKey()
        val recipient = randomKey()
        val recipientPub = NostrEvent.pubkeyFromPrivkey(recipient).hexToBytes()

        val rumor =
            NostrEvent(
                pubkey = "",
                createdAt = 1700000000L,
                kind = 30078,
                tags = listOf(
                    listOf("d", "group:uuid"),
                    listOf("g", "mygroup"),
                    listOf("t", "expense"),
                    listOf("x", "exp-123")
                ),
                content = "encrypted-expense-data-here"
            )
        val wrapped = Nip59.giftWrap(rumor, sender, recipientPub)
        val (recovered, senderPub) = Nip59.unwrap(wrapped, recipient)!!

        assertEquals(NostrEvent.pubkeyFromPrivkey(sender), senderPub)
        assertEquals(rumor.kind, recovered.kind)
        assertEquals(rumor.content, recovered.content)
        assertEquals(rumor.tags, recovered.tags)
        assertEquals(rumor.createdAt, recovered.createdAt)
        assertEquals("", recovered.sig) // rumor is unsigned
    }

    @Test
    fun `same rumor wrapped for different recipients produces different wraps`() {
        val sender = randomKey()
        val r1 = randomKey()
        val r2 = randomKey()
        val rumor = NostrEvent(pubkey = "", createdAt = 1, kind = 1, content = "hello")

        val w1 = Nip59.giftWrap(rumor, sender, NostrEvent.pubkeyFromPrivkey(r1).hexToBytes())
        val w2 = Nip59.giftWrap(rumor, sender, NostrEvent.pubkeyFromPrivkey(r2).hexToBytes())

        assertNotEquals(w1.pubkey, w2.pubkey) // different ephemeral keys
        assertNotEquals(w1.content, w2.content) // different ciphertext

        // Each recipient can unwrap their own
        assertNotNull(Nip59.unwrap(w1, r1))
        assertNotNull(Nip59.unwrap(w2, r2))

        // But not each other's
        assertNull(Nip59.unwrap(w1, r2))
        assertNull(Nip59.unwrap(w2, r1))
    }

    @Test
    fun `gift wrap timestamps are in the past`() {
        val sender = randomKey()
        val recipient = randomKey()
        val recipientPub = NostrEvent.pubkeyFromPrivkey(recipient).hexToBytes()
        val rumor = NostrEvent(pubkey = "", createdAt = 1, kind = 1, content = "test")

        // Wrap 10 times and check all timestamps are in the past
        val now = System.currentTimeMillis() / 1000
        repeat(10) {
            val wrapped = Nip59.giftWrap(rumor, sender, recipientPub)
            assertTrue("Wrap timestamp must be <= now", wrapped.createdAt <= now)
            assertTrue("Wrap timestamp must be within 2 days", wrapped.createdAt > now - 3 * 86400)
        }
    }

    @Test
    fun `unwrap rejects event with kind != 1059`() {
        val sender = randomKey()
        val recipient = randomKey()
        val event =
            NostrEvent(
                pubkey = NostrEvent.pubkeyFromPrivkey(sender),
                createdAt = 1,
                kind = 1,
                content = "not a gift wrap"
            ).sign(sender)
        assertNull(Nip59.unwrap(event, recipient))
    }

    @Test
    fun `unwrap rejects event with invalid signature`() {
        val sender = randomKey()
        val recipient = randomKey()
        val recipientPub = NostrEvent.pubkeyFromPrivkey(recipient).hexToBytes()
        val rumor = NostrEvent(pubkey = "", createdAt = 1, kind = 1, content = "test")
        val wrapped = Nip59.giftWrap(rumor, sender, recipientPub)

        // Tamper with the signature
        val tampered = wrapped.copy(sig = "a".repeat(128))
        assertNull(Nip59.unwrap(tampered, recipient))
    }

    @Test
    fun `gift wrap with large content (NIP-44 encrypted expense JSON)`() {
        val sender = randomKey()
        val recipient = randomKey()
        val recipientPub = NostrEvent.pubkeyFromPrivkey(recipient).hexToBytes()

        // Simulate a real encrypted expense event content (base64 NIP-44 payload)
        val convKey = Nip44.getConversationKey(sender, recipientPub)
        val expenseJson =
            """{"id":"uuid","amount":50000,"currency":"INR",""" +
                """"description":"Dinner at restaurant","paid_by":"abc",""" +
                """"split_type":"equal","split_among":[{"pubkey":"abc",""" +
                """"share":25000},{"pubkey":"def","share":25000}],""" +
                """"timestamp":1700000000}"""
        val encrypted = Nip44.encrypt(expenseJson, convKey)

        val rumor =
            NostrEvent(
                pubkey = "",
                createdAt = 1700000000L,
                kind = 30078,
                tags = listOf(listOf("d", "group:uuid"), listOf("t", "expense")),
                content = encrypted
            )
        val wrapped = Nip59.giftWrap(rumor, sender, recipientPub)
        val (recovered, _) = Nip59.unwrap(wrapped, recipient)!!

        // Verify the encrypted content survived the wrap/unwrap
        val decrypted = Nip44.decrypt(recovered.content, convKey)
        assertEquals(expenseJson, decrypted)
    }
    private fun randomKey(): ByteArray {
        val key = ByteArray(32)
        SecureRandom().nextBytes(key)
        while (!fr.acinq.secp256k1.Secp256k1.secKeyVerify(key)) SecureRandom().nextBytes(key)
        return key
    }
}
