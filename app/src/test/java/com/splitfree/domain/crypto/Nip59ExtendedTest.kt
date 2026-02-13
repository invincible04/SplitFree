package com.splitfree.domain.crypto

import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom

/**
 * Extended NIP-59 tests: edge cases, multi-recipient, content preservation.
 */
class Nip59ExtendedTest {
    private fun randomKey(): ByteArray {
        val key = ByteArray(32)
        SecureRandom().nextBytes(key)
        while (!fr.acinq.secp256k1.Secp256k1
                .secKeyVerify(key)
        ) {
            SecureRandom().nextBytes(key)
        }
        return key
    }

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
                tags = listOf(listOf("d", "group:uuid"), listOf("g", "mygroup"), listOf("t", "expense"), listOf("e", "exp-123")),
                content = "encrypted-expense-data-here",
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
                content = "not a gift wrap",
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
        val expenseJson = """{"id":"uuid","amount":50000,"currency":"INR","description":"Dinner at restaurant","paid_by":"abc","split_type":"equal","split_among":[{"pubkey":"abc","share":25000},{"pubkey":"def","share":25000}],"timestamp":1700000000}"""
        val encrypted = Nip44.encrypt(expenseJson, convKey)

        val rumor =
            NostrEvent(
                pubkey = "",
                createdAt = 1700000000L,
                kind = 30078,
                tags = listOf(listOf("d", "group:uuid"), listOf("t", "expense")),
                content = encrypted,
            )
        val wrapped = Nip59.giftWrap(rumor, sender, recipientPub)
        val (recovered, _) = Nip59.unwrap(wrapped, recipient)!!

        // Verify the encrypted content survived the wrap/unwrap
        val decrypted = Nip44.decrypt(recovered.content, convKey)
        assertEquals(expenseJson, decrypted)
    }
}
