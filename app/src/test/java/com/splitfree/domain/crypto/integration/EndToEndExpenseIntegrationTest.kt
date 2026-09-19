package com.splitfree.domain.crypto.integration

import android.util.Log
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
import com.splitfree.test.RelayProbeAssertions.assertAccepted
import com.splitfree.test.RelayProbeAssertions.requireEvents
import fr.acinq.secp256k1.Secp256k1
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/** Opt-in live crypto/storage round-trips; no app persistence or ledger processing. */
class EndToEndExpenseIntegrationTest {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val encryption = GroupEncryption(CompressionUtil)
    private val relays = listOf("wss://nos.lol", "wss://relay.primal.net")
    private val clients = mutableListOf<NostrClient>()
    private lateinit var scope: CoroutineScope
    private lateinit var privKey: ByteArray
    private lateinit var pubHex: String
    private var logMocked = false

    @Before
    fun setup() {
        Assume.assumeTrue("Skipped: set -DREAL_RELAY_TEST=true", System.getProperty("REAL_RELAY_TEST") == "true")
        mockkStatic(Log::class)
        logMocked = true
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        privKey = generateValidPrivateKey()
        pubHex = NostrEvent.pubkeyFromPrivkey(privKey)
    }

    @After
    fun teardown() {
        try {
            clients.forEach { it.disconnect() }
        } finally {
            if (::scope.isInitialized) scope.cancel()
            if (::privKey.isInitialized) privKey.fill(0)
            if (logMocked) unmockkStatic(Log::class)
        }
    }

    private fun generateValidPrivateKey(): ByteArray {
        val key = ByteArray(32)
        val random = SecureRandom()
        do {
            random.nextBytes(key)
        } while (!Secp256k1.secKeyVerify(key))
        return key
    }

    private suspend fun connectedClient(relayUrl: String): NostrClient {
        val client = NostrClient(scope)
        clients.add(client)
        client.connect(listOf(relayUrl))
        withTimeout(10_000) { client.connectionState.first { it } }
        return client
    }

    private suspend fun publishAndFetch(
        relayUrl: String,
        event: NostrEvent,
        groupId: String,
        recipientPubHex: String = pubHex
    ): NostrEvent {
        assertTrue("Published fixture must verify", event.verify())
        val publisher = connectedClient(relayUrl)
        try {
            assertAccepted(publisher.publish(event))
        } finally {
            publisher.disconnect()
        }
        val reader = connectedClient(relayUrl)
        try {
            val fetched = reader.fetchEvents(groupId, 0, recipientPubHex)
            assertTrue("Independent fetch from $relayUrl must complete without loss", fetched.complete)
            return requireEvents(listOf(event), fetched.events).single()
        } finally {
            reader.disconnect()
        }
    }

    private fun expenseEvent(groupId: String, expense: Expense, groupKey: String): NostrEvent = NostrEvent(
        pubkey = pubHex,
        createdAt = System.currentTimeMillis() / 1000,
        kind = 30078,
        tags = listOf(listOf("d", "$groupId:${expense.id}"), listOf("g", groupId), listOf("t", "expense")),
        content = encryption.encrypt(json.encodeToString(Expense.serializer(), expense), groupKey)
    ).sign(privKey)

    private fun expense(otherPubHex: String): Expense = Expense(
        id = UUID.randomUUID().toString(),
        amount = 50050,
        currency = "INR",
        description = "Disposable dinner fixture at café",
        paidBy = pubHex,
        splitType = SplitType.EQUAL,
        splitAmong = listOf(SplitEntry(pubHex, 25025), SplitEntry(otherPubHex, 25025)),
        timestamp = System.currentTimeMillis() / 1000
    )

    @Test
    fun `expense event - create, sign, publish to relay, fetch back, decrypt`() = runBlocking {
        val otherPriv = generateValidPrivateKey()
        try {
            val groupKey = encryption.generateGroupKey()
            val groupId = UUID.randomUUID().toString()
            val expense = expense(NostrEvent.pubkeyFromPrivkey(otherPriv))
            val event = expenseEvent(groupId, expense, groupKey)
            relays.forEach { url ->
                val fetched = publishAndFetch(url, event, groupId)
                val recovered = json.decodeFromString<Expense>(encryption.decrypt(fetched.content, groupKey))
                assertEquals("Every expense field must survive the remote round-trip on $url", expense, recovered)
            }
        } finally {
            otherPriv.fill(0)
        }
    }

    @Test
    fun `settlement event - publish to relay and fetch back`() = runBlocking {
        val recipientPriv = generateValidPrivateKey()
        try {
            val groupKey = encryption.generateGroupKey()
            val groupId = UUID.randomUUID().toString()
            val settlement = Settlement(
                id = UUID.randomUUID().toString(),
                from = pubHex,
                to = NostrEvent.pubkeyFromPrivkey(recipientPriv),
                amount = 25000,
                currency = "INR",
                timestamp = System.currentTimeMillis() / 1000
            )
            val event = NostrEvent(
                pubkey = pubHex,
                createdAt = System.currentTimeMillis() / 1000,
                kind = 30078,
                tags = listOf(
                    listOf("d", "$groupId:${settlement.id}"),
                    listOf("g", groupId),
                    listOf("t", "settlement")
                ),
                content = encryption.encrypt(json.encodeToString(Settlement.serializer(), settlement), groupKey)
            ).sign(privKey)
            relays.forEach { url ->
                val fetched = publishAndFetch(url, event, groupId)
                val recovered = json.decodeFromString<Settlement>(encryption.decrypt(fetched.content, groupKey))
                assertEquals("Every settlement field must survive the remote round-trip on $url", settlement, recovered)
            }
        } finally {
            recipientPriv.fill(0)
        }
    }

    @Test
    fun `gift-wrapped expense - publish fetch unwrap and decrypt on independent recipient`() = runBlocking {
        val recipientPriv = generateValidPrivateKey()
        try {
            val recipientPubHex = NostrEvent.pubkeyFromPrivkey(recipientPriv)
            val groupKey = encryption.generateGroupKey()
            val groupId = UUID.randomUUID().toString()
            val expense = expense(recipientPubHex)
            val rumor = expenseEvent(groupId, expense, groupKey).copy(sig = "")
            val wrapped = Nip59.giftWrap(rumor, privKey, recipientPubHex.hexToBytes())
            assertEquals(1059, wrapped.kind)
            assertNotEquals(pubHex, wrapped.pubkey)
            relays.forEach { url ->
                val fetched = publishAndFetch(url, wrapped, groupId, recipientPubHex)
                val unwrapped = Nip59.unwrap(fetched, recipientPriv)
                    ?: throw AssertionError("Independent recipient could not unwrap fetched event on $url")
                assertEquals(pubHex, unwrapped.senderPubkey)
                assertEquals(rumor, unwrapped.rumor)
                val recovered = json.decodeFromString<Expense>(encryption.decrypt(unwrapped.rumor.content, groupKey))
                assertEquals(expense, recovered)
            }
        } finally {
            recipientPriv.fill(0)
        }
    }
}
