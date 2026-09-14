package com.splitfree.domain.usecase.expense

import com.splitfree.data.identity.IdentityManager
import com.splitfree.data.nostr.NostrClient
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.SplitEntry
import com.splitfree.domain.model.expense.SplitType
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/**
 * Real relay integration test: two phones create expenses and verify cross-phone sync.
 *
 * Uses live subscriptions (as LiveSync does while the app is visible) instead of fetchEvents
 * to match the actual app behavior and avoid SharedFlow race conditions.
 *
 * Real: secp256k1 keys, NIP-44 encryption, Schnorr signatures, WebSocket relay connections
 * Mocked: Android Log only
 */
class RealRelayExpenseFlowIntegrationTest {
    private lateinit var phone1Client: NostrClient
    private lateinit var phone2Client: NostrClient

    private val encryption = GroupEncryption(com.splitfree.data.util.CompressionUtil)
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var phone1PrivKey: ByteArray
    private lateinit var phone1PubKey: String
    private lateinit var phone2PrivKey: ByteArray
    private lateinit var phone2PubKey: String

    private val phone1Identity = mockk<IdentityManager>()
    private val phone2Identity = mockk<IdentityManager>()
    private lateinit var phone1Signer: EventSigner
    private lateinit var phone2Signer: EventSigner

    private val relays = listOf("wss://relay.snort.social", "wss://nos.lol")

    private lateinit var groupId: String
    private lateinit var groupKey: String
    private lateinit var groupName: String

    @Before
    fun setup() {
        Assume.assumeTrue("Skipped: set -DREAL_RELAY_TEST=true", System.getProperty("REAL_RELAY_TEST") == "true")
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any<String>(), any<String>()) } answers {
            val tag = firstArg<String>()
            val msg = secondArg<String>()
            if (tag in listOf("Relay", "NostrClient")) println("  [$tag] $msg")
            0
        }
        every { android.util.Log.w(any<String>(), any<String>()) } answers {
            val tag = firstArg<String>()
            val msg = secondArg<String>()
            if (tag in listOf("Relay", "NostrClient")) println("  ⚠️ [$tag] $msg")
            0
        }
        every { android.util.Log.d(any<String>(), any<String>()) } answers {
            val tag = firstArg<String>()
            val msg = secondArg<String>()
            if (tag in listOf("Relay", "NostrClient")) println("  [$tag] $msg")
            0
        }
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>(), any()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any()) } returns 0

        phone1PrivKey = generateValidPrivateKey()
        phone1PubKey = NostrEvent.pubkeyFromPrivkey(phone1PrivKey)
        phone2PrivKey = generateValidPrivateKey()
        phone2PubKey = NostrEvent.pubkeyFromPrivkey(phone2PrivKey)

        every { phone1Identity.getPublicKeyHex() } returns phone1PubKey
        every { phone1Identity.getPrivateKeyBytes() } answers { phone1PrivKey.copyOf() }
        every { phone2Identity.getPublicKeyHex() } returns phone2PubKey
        every { phone2Identity.getPrivateKeyBytes() } answers { phone2PrivKey.copyOf() }

        phone1Signer = EventSigner(phone1Identity)
        phone2Signer = EventSigner(phone2Identity)

        phone1Client = NostrClient(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        phone2Client = NostrClient(CoroutineScope(SupervisorJob() + Dispatchers.IO))

        groupId =
            java.util.UUID
                .randomUUID()
                .toString()
        groupKey = encryption.generateGroupKey()
        groupName = "ExpenseTest-${System.currentTimeMillis()}"
    }

    @After
    fun teardown() {
        phone1Client.disconnect()
        phone2Client.disconnect()
        phone1PrivKey.fill(0)
        phone2PrivKey.fill(0)
        unmockkStatic(android.util.Log::class)
    }

    private fun generateValidPrivateKey(): ByteArray {
        val random = java.security.SecureRandom()
        val key = ByteArray(32)
        do {
            random.nextBytes(key)
        } while (!fr.acinq.secp256k1.Secp256k1
                .secKeyVerify(key)
        )
        return key
    }

    @Test(timeout = 90_000)
    fun `full expense flow - create group, add expenses, verify cross-phone sync`() = runBlocking {
        val members = listOf(phone1PubKey, phone2PubKey)
        val now = System.currentTimeMillis() / 1000

        println("=== SETUP ===")
        println("Group: $groupName ($groupId)")
        println("Phone 1: ${phone1PubKey.take(12)}...")
        println("Phone 2: ${phone2PubKey.take(12)}...")

        // ========== Connect both phones ==========
        println("\n=== CONNECTING BOTH PHONES ===")
        phone1Client.authSigner = { c, r -> phone1Signer.createAuthEvent(c, r) }
        phone2Client.authSigner = { c, r -> phone2Signer.createAuthEvent(c, r) }
        phone1Client.connect(relays)
        phone2Client.connect(relays)
        delay(3000)
        assertTrue("Phone 1 connected", phone1Client.isConnected)
        assertTrue("Phone 2 connected", phone2Client.isConnected)
        println("Both phones connected to ${relays.size} relays")

        // ========== Subscribe BOTH phones to the group (the live path LiveSync uses) ==========
        // Events then arrive via incomingEvents; each phone below attaches its collector before the
        // other publishes, so nothing falls into the no-replay gap.
        phone1Client.subscribe(groupId, now - 60)
        phone2Client.subscribe(groupId, now - 60)
        delay(2000) // let subscriptions settle

        // ========== PHONE 1: Publish group_meta ==========
        println("\n=== PHONE 1: Publishing group_meta ===")
        val metaJson =
            buildJsonObject {
                put("name", JsonPrimitive(groupName))
                put("description", JsonPrimitive(""))
                put("created_by", JsonPrimitive(phone1PubKey))
                put("created_at", JsonPrimitive(now))
                putJsonArray("members") { members.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("relays") { relays.forEach { add(JsonPrimitive(it)) } }
            }.toString()
        val metaEvent =
            phone1Signer.createSignedEvent(
                groupId,
                "group_meta",
                encryption.encrypt(metaJson, groupKey)
            )
        assertTrue("group_meta published", phone1Client.publish(metaEvent))
        println("Published group_meta: ${metaEvent.id.take(12)}")

        delay(2000)

        // ========== PHONE 1: Create expense "Dinner ₹1500" ==========
        println("\n=== PHONE 1: Creating expense 'Dinner ₹1500' ===")
        val expense1 =
            Expense(
                id =
                java.util.UUID
                    .randomUUID()
                    .toString(),
                amount = 150000,
                currency = "INR",
                description = "Dinner at restaurant",
                paidBy = phone1PubKey,
                splitType = SplitType.EQUAL,
                splitAmong = listOf(SplitEntry(phone1PubKey, 75000), SplitEntry(phone2PubKey, 75000)),
                timestamp = now,
                category = "food"
            )
        val expense1Event =
            phone1Signer.createSignedEvent(
                groupId = groupId,
                eventType = "expense",
                encryptedContent =
                encryption.encrypt(json.encodeToString(Expense.serializer(), expense1), groupKey),
                expenseUuid = expense1.id
            )
        val deferred1 =
            async {
                withTimeout(30_000) {
                    phone2Client.incomingEvents.first { event ->
                        event.tags.any { it.size >= 2 && it[0] == "t" && it[1] == "expense" }
                    }
                }
            }
        delay(200)
        assertTrue("expense1 published", phone1Client.publish(expense1Event))
        println("Published expense1: ${expense1Event.id.take(12)}")

        // ========== PHONE 2: Wait for expense via real-time subscription ==========
        println("\n=== PHONE 2: Waiting for expense via real-time subscription ===")
        val received1 = deferred1.await()
        println("Phone 2 received: ${received1.id.take(12)} from ${received1.pubkey.take(8)}")
        assertTrue("Signature valid", received1.verify())
        assertEquals(phone1PubKey, received1.pubkey)

        // Decrypt and verify
        val parsed1 =
            json.decodeFromString(
                Expense.serializer(),
                encryption.decrypt(received1.content, groupKey)
            )
        println("  Decrypted: ${parsed1.description} ₹${parsed1.amount / 100}")
        assertEquals("Dinner at restaurant", parsed1.description)
        assertEquals(150000L, parsed1.amount)
        assertEquals(phone1PubKey, parsed1.paidBy)
        assertEquals(75000L, parsed1.splitAmong.find { it.pubkey == phone2PubKey }!!.share)

        delay(2000)

        // ========== PHONE 2: Create expense "Cab ₹400" ==========
        println("\n=== PHONE 2: Creating expense 'Cab ₹400' ===")
        val expense2 =
            Expense(
                id =
                java.util.UUID
                    .randomUUID()
                    .toString(),
                amount = 40000,
                currency = "INR",
                description = "Cab to airport",
                paidBy = phone2PubKey,
                splitType = SplitType.EXACT,
                splitAmong = listOf(SplitEntry(phone1PubKey, 25000), SplitEntry(phone2PubKey, 15000)),
                timestamp = now,
                category = "transport"
            )
        val expense2Event =
            phone2Signer.createSignedEvent(
                groupId = groupId,
                eventType = "expense",
                encryptedContent =
                encryption.encrypt(json.encodeToString(Expense.serializer(), expense2), groupKey),
                expenseUuid = expense2.id
            )
        val deferred2 =
            async {
                withTimeout(30_000) {
                    phone1Client.incomingEvents.first { event ->
                        event.tags.any { it.size >= 2 && it[0] == "t" && it[1] == "expense" } &&
                            event.pubkey == phone2PubKey
                    }
                }
            }
        delay(200)
        assertTrue("expense2 published", phone2Client.publish(expense2Event))
        println("Published expense2: ${expense2Event.id.take(12)}")

        // ========== PHONE 1: Wait for Phone 2's expense ==========
        println("\n=== PHONE 1: Waiting for expense via real-time subscription ===")
        val received2 = deferred2.await()
        println("Phone 1 received: ${received2.id.take(12)} from ${received2.pubkey.take(8)}")
        assertTrue("Signature valid", received2.verify())

        val parsed2 =
            json.decodeFromString(
                Expense.serializer(),
                encryption.decrypt(received2.content, groupKey)
            )
        println("  Decrypted: ${parsed2.description} ₹${parsed2.amount / 100}")
        assertEquals("Cab to airport", parsed2.description)
        assertEquals(40000L, parsed2.amount)
        assertEquals(phone2PubKey, parsed2.paidBy)

        // ========== Balance verification ==========
        println("\n=== BALANCE CALCULATION ===")
        val balances = mutableMapOf<String, Long>()
        for (exp in listOf(parsed1, parsed2)) {
            balances[exp.paidBy] = (balances[exp.paidBy] ?: 0) + exp.amount
            for (split in exp.splitAmong) {
                balances[split.pubkey] = (balances[split.pubkey] ?: 0) - split.share
            }
        }
        val p1Bal = balances[phone1PubKey] ?: 0
        val p2Bal = balances[phone2PubKey] ?: 0
        println("Phone 1: ₹${p1Bal / 100} (${if (p1Bal > 0) "is owed" else "owes"})")
        println("Phone 2: ₹${p2Bal / 100} (${if (p2Bal > 0) "is owed" else "owes"})")
        assertEquals("Phone 1 owed ₹500", 50000L, p1Bal)
        assertEquals("Phone 2 owes ₹500", -50000L, p2Bal)

        println("\n✅ FULL EXPENSE FLOW PASSED")
        println("   Phone 1 → 'Dinner ₹1500' → relay → Phone 2 received & decrypted ✓")
        println("   Phone 2 → 'Cab ₹400' → relay → Phone 1 received & decrypted ✓")
        println("   Balance: Phone 2 owes Phone 1 ₹500 ✓")
    }
}
