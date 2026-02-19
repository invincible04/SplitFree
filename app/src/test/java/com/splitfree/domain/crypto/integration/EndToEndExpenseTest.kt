package com.splitfree.domain.crypto.integration

import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.nip.Nip59
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.Settlement
import com.splitfree.domain.model.expense.SplitEntry
import com.splitfree.domain.model.expense.SplitType
import com.splitfree.domain.util.hexToBytes
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end test: simulates the full lifecycle of an expense event
 * from creation through encryption, signing, JSON serialization,
 * relay round-trip simulation, and decryption back to the original data.
 *
 * This is the closest we can get to a real-app test without Android.
 */
class EndToEndExpenseTest {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val encryption = GroupEncryption(com.splitfree.data.util.CompressionUtil)

    @Test
    fun `full expense lifecycle - create, sign, serialize, parse, verify, decrypt`() {
        // 1. Generate identity
        val privKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        while (!fr.acinq.secp256k1.Secp256k1
                .secKeyVerify(privKey)
        ) {
            SecureRandom().nextBytes(privKey)
        }
        val pubHex = NostrEvent.pubkeyFromPrivkey(privKey)

        // 2. Generate group key
        val groupKey = encryption.generateGroupKey()
        val groupId = "test-group-${System.currentTimeMillis()}"

        // 3. Create expense
        val expense =
            Expense(
                id = "exp-001",
                amount = 50050, // ₹500.50
                currency = "INR",
                description = "Dinner at café 🍕",
                paidBy = pubHex,
                splitType = SplitType.EQUAL,
                splitAmong =
                listOf(
                    SplitEntry(pubHex, 25025),
                    SplitEntry("other-member-pubkey", 25025)
                ),
                timestamp = System.currentTimeMillis() / 1000,
                category = "food"
            )

        // 4. Serialize and encrypt
        val plaintext =
            json.encodeToString(
                Expense
                    .serializer(),
                expense
            )
        val encrypted = encryption.encrypt(plaintext, groupKey)

        // 5. Create and sign Nostr event
        val dTagValue = "$groupId:${expense.id}"
        val event =
            NostrEvent(
                pubkey = pubHex,
                createdAt = System.currentTimeMillis() / 1000,
                kind = 30078,
                tags =
                listOf(
                    listOf("d", dTagValue),
                    listOf("g", groupId),
                    listOf("t", "expense"),
                    listOf("x", expense.id)
                ),
                content = encrypted
            ).sign(privKey)

        // 6. Verify the signed event
        assertTrue("Event must verify", event.verify())
        assertEquals(30078, event.kind)

        // 7. Simulate relay: serialize to JSON and parse back
        val wireJson = event.toJson()
        val received = NostrEvent.fromJson(wireJson)!!

        // 8. Verify received event
        assertTrue("Received event must verify", received.verify())
        assertEquals(event.id, received.id)
        assertEquals(event.sig, received.sig)

        // 9. Extract tags
        val gTag = received.tags.find { it[0] == "g" }!![1]
        val tTag = received.tags.find { it[0] == "t" }!![1]
        val eTag = received.tags.find { it[0] == "x" }!![1]
        assertEquals(groupId, gTag)
        assertEquals("expense", tTag)
        assertEquals("exp-001", eTag)

        // 10. Decrypt content
        val decrypted = encryption.decrypt(received.content, groupKey)
        val recoveredExpense = json.decodeFromString<Expense>(decrypted)

        // 11. Verify all fields match
        assertEquals(expense.id, recoveredExpense.id)
        assertEquals(expense.amount, recoveredExpense.amount)
        assertEquals(expense.currency, recoveredExpense.currency)
        assertEquals(expense.description, recoveredExpense.description)
        assertEquals(expense.paidBy, recoveredExpense.paidBy)
        assertEquals(expense.splitType, recoveredExpense.splitType)
        assertEquals(expense.splitAmong, recoveredExpense.splitAmong)
        assertEquals(expense.category, recoveredExpense.category)

        // 12. Verify balance computation
        val balances = mutableMapOf<Pair<String, String>, Long>()
        for (split in recoveredExpense.splitAmong) {
            if (split.pubkey != recoveredExpense.paidBy) {
                val payerKey = recoveredExpense.paidBy to recoveredExpense.currency
                val debtorKey = split.pubkey to recoveredExpense.currency
                balances[payerKey] = (balances[payerKey] ?: 0L) + split.share
                balances[debtorKey] = (balances[debtorKey] ?: 0L) - split.share
            }
        }
        assertEquals(25025L, balances[pubHex to "INR"])
        assertEquals(-25025L, balances["other-member-pubkey" to "INR"])

        // Zero key
        privKey.fill(0)
    }

    @Test
    fun `full gift-wrapped expense lifecycle`() {
        // Sender
        val senderPriv = ByteArray(32).also { SecureRandom().nextBytes(it) }
        while (!fr.acinq.secp256k1.Secp256k1
                .secKeyVerify(senderPriv)
        ) {
            SecureRandom().nextBytes(senderPriv)
        }
        val senderPub = NostrEvent.pubkeyFromPrivkey(senderPriv)

        // Recipient
        val recipientPriv = ByteArray(32).also { SecureRandom().nextBytes(it) }
        while (!fr.acinq.secp256k1.Secp256k1
                .secKeyVerify(recipientPriv)
        ) {
            SecureRandom().nextBytes(recipientPriv)
        }
        val recipientPub = NostrEvent.pubkeyFromPrivkey(recipientPriv).hexToBytes()

        // Group key
        val groupKey = encryption.generateGroupKey()

        // Create expense event
        val expenseJson =
            """{"id":"e1","amount":1000,"currency":"INR","description":"test",""" +
                """"paid_by":"$senderPub","split_type":"equal",""" +
                """"split_among":[{"pubkey":"$senderPub","share":500},""" +
                """{"pubkey":"other","share":500}],"timestamp":1}"""
        val encrypted = encryption.encrypt(expenseJson, groupKey)
        val inner =
            NostrEvent(
                pubkey = senderPub,
                createdAt = System.currentTimeMillis() / 1000,
                kind = 30078,
                tags = listOf(listOf("g", "group1"), listOf("t", "expense")),
                content = encrypted
            ).sign(senderPriv)

        // Gift wrap
        val wrapped = Nip59.giftWrap(inner.copy(sig = ""), senderPriv, recipientPub)
        assertEquals(1059, wrapped.kind)
        assertNotEquals(senderPub, wrapped.pubkey) // ephemeral key

        // Simulate relay: serialize and parse
        val wireWrapped = NostrEvent.fromJson(wrapped.toJson())!!
        assertTrue(wireWrapped.verify())

        // Recipient unwraps
        val (rumor, senderPubRecovered) = Nip59.unwrap(wireWrapped, recipientPriv)!!
        assertEquals(senderPub, senderPubRecovered)
        assertEquals(30078, rumor.kind)

        // Decrypt the expense
        val decrypted = encryption.decrypt(rumor.content, groupKey)
        assertTrue(decrypted.contains("\"amount\":1000"))
        assertTrue(decrypted.contains("\"currency\":\"INR\""))

        senderPriv.fill(0)
        recipientPriv.fill(0)
    }

    @Test
    fun `settlement event lifecycle`() {
        val privKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        while (!fr.acinq.secp256k1.Secp256k1
                .secKeyVerify(privKey)
        ) {
            SecureRandom().nextBytes(privKey)
        }
        val pubHex = NostrEvent.pubkeyFromPrivkey(privKey)
        val groupKey = encryption.generateGroupKey()

        val settlement =
            Settlement(
                id = "s1",
                from = pubHex,
                to = "creditor-pub",
                amount = 25000,
                currency = "INR",
                method = "upi",
                timestamp = System.currentTimeMillis() / 1000
            )
        val plaintext =
            json.encodeToString(
                Settlement
                    .serializer(),
                settlement
            )
        val encrypted = encryption.encrypt(plaintext, groupKey)

        val event =
            NostrEvent(
                pubkey = pubHex,
                createdAt = System.currentTimeMillis() / 1000,
                kind = 30078,
                tags = listOf(listOf("d", "group:${settlement.id}"), listOf("t", "settlement")),
                content = encrypted
            ).sign(privKey)

        // Round-trip
        val received = NostrEvent.fromJson(event.toJson())!!
        assertTrue(received.verify())
        val decrypted = encryption.decrypt(received.content, groupKey)
        val recovered = json.decodeFromString<Settlement>(decrypted)
        assertEquals(settlement, recovered)

        privKey.fill(0)
    }
}
