package com.splitfree.domain.crypto

import org.junit.Assert.*
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
    fun `unwrap fails with wrong recipient key`() {
        val wrapped = Nip59.giftWrap(makeRumor(), senderPriv, recipientPub)
        val wrongKey = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc".hexToBytes()
        val result = Nip59.unwrap(wrapped, wrongKey)
        assertNull("Unwrap with wrong key should fail", result)
    }

    @Test
    fun `unwrap rejects non-gift-wrap events`() {
        val event = NostrEvent(
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
    fun `unwrap rejects seal with wrong kind`() {
        val ephPriv = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc".hexToBytes()
        val ephPub = NostrEvent.pubkeyFromPrivkey(ephPriv)
        val fakeSeal = NostrEvent(
            pubkey = senderPub, createdAt = 1L, kind = 1,
            tags = emptyList(), content = "fake"
        ).sign(senderPriv)
        val wrapConvKey = Nip44.getConversationKey(ephPriv, recipientPub)
        val wrapContent = Nip44.encrypt(fakeSeal.toJson(), wrapConvKey)
        val fakeWrap = NostrEvent(
            pubkey = ephPub, createdAt = 1L, kind = 1059,
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
        val giftWrapJson = """{"id":"5c005f3ccf01950aa8d131203248544fb1e41a0d698e846bd419cec3890903ac","pubkey":"18b1a75918f1f2c90c23da616bce317d36e348bcf5f7ba55e75949319210c87c","created_at":1703021488,"kind":1059,"tags":[["p","166bf3765ebd1fc55decfe395beff2ea3b2a4e0a8946e7eb578512b555737c99"]],"content":"AhC3Qj/QsKJFWuf6xroiYip+2yK95qPwJjVvFujhzSguJWb/6TlPpBW0CGFwfufCs2Zyb0JeuLmZhNlnqecAAalC4ZCugB+I9ViA5pxLyFfQjs1lcE6KdX3euCHBLAnE9GL/+IzdV9vZnfJH6atVjvBkNPNzxU+OLCHO/DAPmzmMVx0SR63frRTCz6Cuth40D+VzluKu1/Fg2Q1LSst65DE7o2efTtZ4Z9j15rQAOZfE9jwMCQZt27rBBK3yVwqVEriFpg2mHXc1DDwHhDADO8eiyOTWF1ghDds/DxhMcjkIi/o+FS3gG1dG7gJHu3KkGK5UXpmgyFKt+421m5o++RMD/BylS3iazS1S93IzTLeGfMCk+7IKxuSCO06k1+DaasJJe8RE4/rmismUvwrHu/HDutZWkvOAhd4z4khZo7bJLtiCzZCZ74lZcjOB4CYtuAX2ZGpc4I1iOKkvwTuQy9BWYpkzGg3ZoSWRD6ty7U+KN+fTTmIS4CelhBTT15QVqD02JxfLF7nA6sg3UlYgtiGw61oH68lSbx16P3vwSeQQpEB5JbhofW7t9TLZIbIW/ODnI4hpwj8didtk7IMBI3Ra3uUP7ya6vptkd9TwQkd/7cOFaSJmU+BIsLpOXbirJACMn+URoDXhuEtiO6xirNtrPN8jYqpwvMUm5lMMVzGT3kMMVNBqgbj8Ln8VmqouK0DR+gRyNb8fHT0BFPwsHxDskFk5yhe5c/2VUUoKCGe0kfCcX/EsHbJLUUtlHXmTqaOJpmQnW1tZ/siPwKRl6oEsIJWTUYxPQmrM2fUpYZCuAo/29lTLHiHMlTbarFOd6J/ybIbICy2gRRH/LFSryty3Cnf6aae+A9uizFBUdCwTwffc3vCBae802+R92OL78bbqHKPbSZOXNC+6ybqziezwG+OPWHx1Qk39RYaF0aFsM4uZWrFic97WwVrH5i+/Nsf/OtwWiuH0gV/SqvN1hnkxCTF/+XNn/laWKmS3e7wFzBsG8+qwqwmO9aVbDVMhOmeUXRMkxcj4QreQkHxLkCx97euZpC7xhvYnCHarHTDeD6nVK+xzbPNtzeGzNpYoiMqxZ9bBJwMaHnEoI944Vxoodf51cMIIwpTmmRvAzI1QgrfnOLOUS7uUjQ/IZ1Qa3lY08Nqm9MAGxZ2Ou6R0/Z5z30ha/Q71q6meAs3uHQcpSuRaQeV29IASmye2A2Nif+lmbhV7w8hjFYoaLCRsdchiVyNjOEM4VmxUhX4VEvw6KoCAZ/XvO2eBF/SyNU3Of4SO","sig":"35fabdae4634eb630880a1896a886e40fd6ea8a60958e30b89b33a93e6235df750097b04f9e13053764251b8bc5dd7e8e0794a3426a90b6bcc7e5ff660f54259"}"""
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
}
