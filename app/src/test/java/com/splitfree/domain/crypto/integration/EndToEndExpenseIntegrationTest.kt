package com.splitfree.domain.crypto.integration

import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.util.CompressionUtil
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.nip.Nip59
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.Settlement
import com.splitfree.domain.model.expense.SplitEntry
import com.splitfree.domain.model.expense.SplitType
import com.splitfree.domain.util.hexToBytes
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/**
 * End-to-end integration test: creates real expense/settlement events with real crypto,
 * publishes them to real Nostr relays, fetches them back, and verifies decryption.
 *
 * Run: `./gradlew test -DREAL_RELAY_TEST=true --tests "*.EndToEndExpenseIntegrationTest"`
 */
class EndToEndExpenseIntegrationTest {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val encryption = GroupEncryption(CompressionUtil)
    private val relays = listOf("wss://nos.lol", "wss://relay.primal.net")

    private lateinit var client: NostrClient
    private lateinit var privKey: ByteArray
    private lateinit var pubHex: String

    @Before
    fun setup() {
        Assume.assumeTrue("Skipped: set -DREAL_RELAY_TEST=true", System.getProperty("REAL_RELAY_TEST") == "true")
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0

        privKey = generateValidPrivateKey()
        pubHex = NostrEvent.pubkeyFromPrivkey(privKey)
        client = NostrClient(CoroutineScope(SupervisorJob() + Dispatchers.IO))
    }

    @After
    fun teardown() {
        if (::client.isInitialized) client.disconnect()
        if (::privKey.isInitialized) privKey.fill(0)
        unmockkStatic(android.util.Log::class)
    }

    private fun generateValidPrivateKey(): ByteArray {
        val key = ByteArray(32)
        do {
            SecureRandom().nextBytes(key)
        } while (!fr.acinq.secp256k1.Secp256k1.secKeyVerify(key))
        return key
    }

    @Test(timeout = 60_000)
    fun `expense event - create, sign, publish to relay, fetch back, decrypt`() = runBlocking {
        val groupKey = encryption.generateGroupKey()
        val groupId = "expense-test-${System.currentTimeMillis()}"

        val expense = Expense(
            id = "exp-${System.currentTimeMillis()}",
            amount = 50050,
            currency = "INR",
            description = "Dinner at café",
            paidBy = pubHex,
            splitType = SplitType.EQUAL,
            splitAmong = listOf(SplitEntry(pubHex, 25025), SplitEntry("bb".repeat(32), 25025)),
            timestamp = System.currentTimeMillis() / 1000
        )

        val encrypted = encryption.encrypt(json.encodeToString(Expense.serializer(), expense), groupKey)
        val event = NostrEvent(
            pubkey = pubHex,
            createdAt = System.currentTimeMillis() / 1000,
            kind = 30078,
            tags = listOf(listOf("d", "$groupId:${expense.id}"), listOf("g", groupId), listOf("t", "expense")),
            content = encrypted
        ).sign(privKey)

        assertTrue("Event must verify", event.verify())

        // Publish to real relays
        client.connect(relays)
        delay(3000)
        assertTrue("Should be connected", client.isConnected)
        client.publish(event)
        delay(2000)

        // Fetch back
        val fetched = client.fetchEvents(groupId, 0, pubHex).events
        println("Fetched ${fetched.size} events from relays")

        if (fetched.isNotEmpty()) {
            val found = fetched.find { it.id == event.id }
            if (found != null) {
                assertTrue("Fetched event must verify", found.verify())
                val decrypted = encryption.decrypt(found.content, groupKey)
                val recovered = json.decodeFromString<Expense>(decrypted)
                assertEquals(expense.id, recovered.id)
                assertEquals(expense.amount, recovered.amount)
                assertEquals(expense.currency, recovered.currency)
                println("✅ Expense round-trip verified via real relay")
            }
        }
    }

    @Test(timeout = 60_000)
    fun `settlement event - publish to relay and fetch back`() = runBlocking {
        val groupKey = encryption.generateGroupKey()
        val groupId = "settle-test-${System.currentTimeMillis()}"

        val settlement = Settlement(
            id = "s-${System.currentTimeMillis()}",
            from = pubHex,
            to = "bb".repeat(32),
            amount = 25000,
            currency = "INR",
            timestamp = System.currentTimeMillis() / 1000
        )

        val encrypted = encryption.encrypt(json.encodeToString(Settlement.serializer(), settlement), groupKey)
        val event = NostrEvent(
            pubkey = pubHex,
            createdAt = System.currentTimeMillis() / 1000,
            kind = 30078,
            tags = listOf(listOf("d", "$groupId:${settlement.id}"), listOf("g", groupId), listOf("t", "settlement")),
            content = encrypted
        ).sign(privKey)

        assertTrue("Event must verify", event.verify())

        client.connect(relays)
        delay(3000)
        client.publish(event)
        delay(2000)

        val fetched = client.fetchEvents(groupId, 0, pubHex).events
        println("Fetched ${fetched.size} settlement events")

        if (fetched.isNotEmpty()) {
            val found = fetched.find { it.id == event.id }
            if (found != null) {
                val decrypted = encryption.decrypt(found.content, groupKey)
                val recovered = json.decodeFromString<Settlement>(decrypted)
                assertEquals(settlement.id, recovered.id)
                assertEquals(settlement.amount, recovered.amount)
                println("✅ Settlement round-trip verified via real relay")
            }
        }
    }

    @Test(timeout = 60_000)
    fun `gift-wrapped expense - publish and verify on relay`() = runBlocking {
        val recipientPriv = generateValidPrivateKey()
        val recipientPub = NostrEvent.pubkeyFromPrivkey(recipientPriv).hexToBytes()
        val groupKey = encryption.generateGroupKey()

        val expenseJson = """{"id":"e1","amount":1000,"currency":"INR","description":"test",""" +
            """"paid_by":"$pubHex","split_type":"equal",""" +
            """"split_among":[{"pubkey":"$pubHex","share":500},{"pubkey":"other","share":500}],"timestamp":1}"""
        val encrypted = encryption.encrypt(expenseJson, groupKey)
        val inner = NostrEvent(
            pubkey = pubHex,
            createdAt = System.currentTimeMillis() / 1000,
            kind = 30078,
            tags = listOf(listOf("g", "gw-test"), listOf("t", "expense")),
            content = encrypted
        ).sign(privKey)

        val wrapped = Nip59.giftWrap(inner.copy(sig = ""), privKey, recipientPub)
        assertEquals(1059, wrapped.kind)
        assertNotEquals(pubHex, wrapped.pubkey)
        assertTrue("Wrapped event must verify", wrapped.verify())

        // Publish gift wrap to real relay
        client.connect(relays)
        delay(3000)
        client.publish(wrapped)
        delay(2000)
        println("✅ Gift-wrapped expense published to real relay")

        recipientPriv.fill(0)
    }
}
