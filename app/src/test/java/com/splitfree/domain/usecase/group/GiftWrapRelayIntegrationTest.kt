package com.splitfree.domain.usecase.group

import com.splitfree.data.identity.IdentityManager
import com.splitfree.data.nostr.NostrClient
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.nip.Nip59
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.SplitEntry
import com.splitfree.domain.model.expense.SplitType
import com.splitfree.test.RelayProbeAssertions.assertAccepted
import com.splitfree.test.RelayProbeAssertions.requireEvents
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/**
 * Live NIP-59 and bidirectional direct-event transport between two clients in one JVM.
 * Verifies exact events and decrypted expenses, not app persistence or ledger processing.
 * The recipient subscription uses an unrelated #g value to isolate kind-1059 delivery via #p.
 * Opt in with `-DREAL_RELAY_TEST=true`; rejection or missing exact events fails.
 */
class GiftWrapRelayIntegrationTest {
    private lateinit var relayScope: CoroutineScope
    private var logMocked = false

    private lateinit var phone1: NostrClient
    private lateinit var phone2: NostrClient
    private val encryption = GroupEncryption(com.splitfree.data.util.CompressionUtil)
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var p1Priv: ByteArray
    private lateinit var p1Pub: String
    private lateinit var p2Priv: ByteArray
    private lateinit var p2Pub: String
    private lateinit var p1Signer: EventSigner
    private lateinit var p2Signer: EventSigner

    private val relay = "wss://relay.snort.social"
    private val relays = listOf(relay, "wss://nos.lol")
    private lateinit var groupId: String
    private lateinit var groupKey: String

    @Before
    fun setup() {
        Assume.assumeTrue("Skipped: set -DREAL_RELAY_TEST=true", System.getProperty("REAL_RELAY_TEST") == "true")
        mockkStatic(android.util.Log::class)
        logMocked = true
        relayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } answers {
            val tag = firstArg<String>()
            val msg = secondArg<String>()
            if (tag == "Relay") println("  [$tag] $msg")
            0
        }
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>(), any()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any()) } returns 0

        p1Priv = genKey()
        p1Pub = NostrEvent.pubkeyFromPrivkey(p1Priv)
        p2Priv = genKey()
        p2Pub = NostrEvent.pubkeyFromPrivkey(p2Priv)

        val p1Id = mockk<IdentityManager>()
        every { p1Id.getPublicKeyHex() } returns p1Pub
        every { p1Id.getPrivateKeyBytes() } answers { p1Priv.copyOf() }
        val p2Id = mockk<IdentityManager>()
        every { p2Id.getPublicKeyHex() } returns p2Pub
        every { p2Id.getPrivateKeyBytes() } answers { p2Priv.copyOf() }

        p1Signer = EventSigner(p1Id)
        p2Signer = EventSigner(p2Id)
        phone1 = NostrClient(relayScope)
        phone2 = NostrClient(relayScope)

        groupId =
            java.util.UUID
                .randomUUID()
                .toString()
        groupKey = encryption.generateGroupKey()
    }

    @After
    fun teardown() {
        if (::phone1.isInitialized) phone1.disconnect()
        if (::phone2.isInitialized) phone2.disconnect()
        if (::relayScope.isInitialized) relayScope.cancel()
        if (::p1Priv.isInitialized) p1Priv.fill(0)
        if (::p2Priv.isInitialized) p2Priv.fill(0)
        if (logMocked) unmockkStatic(android.util.Log::class)
    }

    private fun genKey(): ByteArray {
        val r = java.security.SecureRandom()
        val k = ByteArray(32)
        do {
            r.nextBytes(k)
        } while (!fr.acinq.secp256k1.Secp256k1
                .secKeyVerify(k)
        )
        return k
    }

    @Test(timeout = 90_000)
    fun `gift wrapped expense is received via p-tag routing`() = runBlocking {
        val now = System.currentTimeMillis() / 1000
        println("=== GIFT WRAP RELAY TEST ===")
        println("Phone 1: ${p1Pub.take(8)}  Phone 2: ${p2Pub.take(8)}")

        phone1.authSigner = { c, r -> p1Signer.createAuthEvent(c, r) }
        phone2.authSigner = { c, r -> p2Signer.createAuthEvent(c, r) }
        phone1.connect(relays)
        phone2.connect(relays)
        withTimeout(15_000) {
            phone1.connectionState.first { it }
            phone2.connectionState.first { it }
        }
        assertTrue("Both connected", phone1.isConnected && phone2.isConnected)
        assertTrue("Sender relay pool must answer before publishing", phone1.fetchEvents(groupId, 0, p1Pub).complete)
        assertTrue("Recipient relay pool must answer before publishing", phone2.fetchEvents(groupId, 0, p2Pub).complete)

        println("\n── TEST 1: Verify gift wrap tag structure ──")
        val expense =
            Expense(
                id =
                java.util.UUID
                    .randomUUID()
                    .toString(),
                amount = 150000,
                currency = "INR",
                description = "Dinner",
                paidBy = p1Pub,
                splitType = SplitType.EQUAL,
                splitAmong = listOf(SplitEntry(p1Pub, 75000), SplitEntry(p2Pub, 75000)),
                timestamp = now,
                category = "food"
            )
        val expenseEvent =
            p1Signer.createSignedEvent(
                groupId,
                "expense",
                encryption.encrypt(json.encodeToString(Expense.serializer(), expense), groupKey),
                expense.id
            )
        val wrapped =
            Nip59.giftWrap(
                rumor = expenseEvent.copy(sig = ""),
                senderPrivKey = p1Priv.copyOf(),
                recipientPubKey =
                p2Pub.let { hex ->
                    ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                }
            )

        // NIP-59 requires the recipient p tag; the g tag is a SplitFree extension.
        val pTag = wrapped.tags.find { it.size >= 2 && it[0] == "p" }
        val gTag = wrapped.tags.find { it.size >= 2 && it[0] == "g" }
        assertNotNull("NIP-59: must have p tag for recipient routing", pTag)
        assertNotNull("App: must have g tag for group filtering", gTag)
        assertEquals(p2Pub, pTag!![1])
        assertEquals(groupId, gTag!![1])
        assertEquals(1059, wrapped.kind)
        assertTrue("Wrapped event must verify", wrapped.verify())
        println("   ✅ p tag: ${pTag[1].take(8)} (recipient)")
        println("   ✅ g tag: ${gTag[1].take(8)} (group)")
        println("   ✅ kind: 1059, ephemeral pubkey: ${wrapped.pubkey.take(8)}")

        val wrappedDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(30_000) {
                phone2.incomingEvents.first { event ->
                    event.id == wrapped.id &&
                        event.kind == 1059 &&
                        event.tags.any { it.size >= 2 && it[0] == "p" && it[1] == p2Pub }
                }
            }
        }
        // A different #g filter makes this envelope reachable only through the recipient #p filter.
        val recipientSubscription = java.util.UUID.randomUUID().toString()
        phone2.subscribe(recipientSubscription, now, p2Pub)
        assertAccepted(phone1.publish(wrapped))
        val receivedWrap = requireEvents(listOf(wrapped), listOf(wrappedDeferred.await())).single()
        val unwrapped = Nip59.unwrap(receivedWrap, p2Priv.copyOf())
        assertNotNull("Received envelope must unwrap for its recipient", unwrapped)
        val (rumor, sender) = unwrapped!!
        assertEquals(p1Pub, sender)
        assertEquals(expenseEvent.copy(sig = ""), rumor)
        val parsed = json.decodeFromString(
            Expense.serializer(),
            encryption.decrypt(rumor.content, groupKey)
        )
        assertEquals(expense, parsed)
        assertTrue("Rumor must carry the expense UUID", listOf("x", expense.id) in rumor.tags)
        assertNull("Rumor must NOT have e tag", rumor.tags.find { it.firstOrNull() == "e" })
        phone2.unsubscribe(recipientSubscription)

        println("\n── TEST 3: Direct expense sync (gift wrap disabled path) ──")
        phone1.subscribe(groupId, now - 60)
        phone2.subscribe(groupId, now - 60)

        val directExpense =
            p1Signer.createSignedEvent(
                groupId,
                "expense",
                encryption.encrypt(json.encodeToString(Expense.serializer(), expense), groupKey),
                expense.id + "-direct"
            )
        val receivedDeferred =
            async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(30_000) {
                    phone2.incomingEvents.first { e ->
                        e.id == directExpense.id && e.kind == 30078
                    }
                }
            }
        assertAccepted(phone1.publish(directExpense))

        val received = requireEvents(listOf(directExpense), listOf(receivedDeferred.await())).single()
        val directParsed =
            json.decodeFromString(
                Expense.serializer(),
                encryption.decrypt(received.content, groupKey)
            )
        assertEquals(expense, directParsed)
        println("   ✅ Phone 2 received direct expense: ${directParsed.description} ₹${directParsed.amount / 100}")

        println("\n── TEST 4: Phone 2 → Phone 1 direct expense ──")
        val exp2 =
            Expense(
                id =
                java.util.UUID
                    .randomUUID()
                    .toString(),
                amount = 40000,
                currency = "INR",
                description = "Cab",
                paidBy = p2Pub,
                splitType = SplitType.EQUAL,
                splitAmong = listOf(SplitEntry(p1Pub, 20000), SplitEntry(p2Pub, 20000)),
                timestamp = now,
                category = "transport"
            )
        val exp2Event =
            p2Signer.createSignedEvent(
                groupId,
                "expense",
                encryption.encrypt(json.encodeToString(Expense.serializer(), exp2), groupKey),
                exp2.id
            )
        val recv2Deferred =
            async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(30_000) {
                    phone1.incomingEvents.first { e ->
                        e.id == exp2Event.id && e.kind == 30078 && e.pubkey == p2Pub
                    }
                }
            }
        assertAccepted(phone2.publish(exp2Event))

        val recv2 = requireEvents(listOf(exp2Event), listOf(recv2Deferred.await())).single()
        val parsed2 =
            json.decodeFromString(
                Expense.serializer(),
                encryption.decrypt(recv2.content, groupKey)
            )
        assertEquals(exp2, parsed2)
        println("   ✅ Phone 1 received: ${parsed2.description} ₹${parsed2.amount / 100}")

        println("\n╔══════════════════════════════════════════════════╗")
        println("║  ✅ ALL TESTS PASSED                              ║")
        println("╠══════════════════════════════════════════════════╣")
        println("║  ✓ Gift wrap has p tag (NIP-59 routing)          ║")
        println("║  ✓ Gift wrap has g tag (group filtering)         ║")
        println("║  ✓ Received exact envelope + unwrap + decrypt    ║")
        println("║  ✓ x tag (not e) for expense UUID                ║")
        println("║  ✓ Relay accepts kind 1059                       ║")
        println("║  ✓ Direct expense sync Phone 1→2                 ║")
        println("║  ✓ Direct expense sync Phone 2→1                 ║")
        println("╚══════════════════════════════════════════════════╝")
    }
}
