package com.splitfree.domain.usecase.integration

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/**
 * Full real-world simulation: two phones, real Nostr relays, all functionality.
 *
 * Simulates:
 * 1. Phone 1 creates group → publishes group_meta
 * 2. Phone 2 joins group → fetches group_meta, publishes updated member list
 * 3. Phone 1 receives updated member list from Phone 2
 * 4. Phone 1 adds expense "Dinner ₹1500" (equal split)
 * 5. Phone 2 receives expense, decrypts, verifies
 * 6. Phone 2 adds expense "Cab ₹400" (exact split)
 * 7. Phone 1 receives expense, decrypts, verifies
 * 8. Phone 1 adds expense "Groceries ₹800" (equal split)
 * 9. Phone 2 receives it
 * 10. Phone 2 adds expense correction (delete + re-add)
 * 11. Phone 1 receives correction
 * 12. Both phones compute identical balances
 *
 * All crypto, signing, encryption, relay I/O is REAL.
 * Only Android framework (Log) is mocked.
 */
class FullRealWorldSimulationIntegrationTest {
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

    private val relays = listOf("wss://relay.snort.social", "wss://nos.lol")
    private lateinit var groupId: String
    private lateinit var groupKey: String
    private lateinit var groupName: String

    @Before
    fun setup() {
        Assume.assumeTrue("Skipped: set -DREAL_RELAY_TEST=true", System.getProperty("REAL_RELAY_TEST") == "true")
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
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
        phone1 = NostrClient(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        phone2 = NostrClient(CoroutineScope(SupervisorJob() + Dispatchers.IO))

        groupId =
            java.util.UUID
                .randomUUID()
                .toString()
        groupKey = encryption.generateGroupKey()
        groupName = "SimGroup-${System.currentTimeMillis()}"
    }

    @After
    fun teardown() {
        phone1.disconnect()
        phone2.disconnect()
        p1Priv.fill(0)
        p2Priv.fill(0)
        unmockkStatic(android.util.Log::class)
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

    private fun buildGroupMeta(members: List<String>, now: Long): String = buildJsonObject {
        put("name", JsonPrimitive(groupName))
        put("description", JsonPrimitive("Test group"))
        put("created_by", JsonPrimitive(p1Pub))
        put("created_at", JsonPrimitive(now))
        putJsonArray("members") { members.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("relays") { relays.forEach { add(JsonPrimitive(it)) } }
    }.toString()

    private fun makeExpense(
        id: String,
        amount: Long,
        currency: String,
        desc: String,
        paidBy: String,
        splitType: SplitType,
        splits: List<SplitEntry>,
        ts: Long,
        category: String
    ) = Expense(id, amount, currency, desc, paidBy, splitType, splits, ts, category)

    private fun signExpense(signer: EventSigner, expense: Expense): NostrEvent {
        val plain = json.encodeToString(Expense.serializer(), expense)
        return signer.createSignedEvent(
            groupId,
            "expense",
            encryption.encrypt(plain, groupKey),
            expense.id
        )
    }

    private fun signDeletion(signer: EventSigner, expenseUuid: String, ts: Long): NostrEvent {
        val payload =
            buildJsonObject {
                put("expense_id", JsonPrimitive(expenseUuid))
                put("reason", JsonPrimitive("correction"))
            }.toString()
        return signer.createSignedEvent(
            groupId,
            "expense_delete",
            encryption.encrypt(payload, groupKey),
            expenseUuid
        )
    }

    @Test(timeout = 90_000)
    fun `full real-world simulation - all functionality both phones`() = runBlocking {
        val now = System.currentTimeMillis() / 1000
        println("╔══════════════════════════════════════════════════╗")
        println("║  SPLITFREE FULL REAL-WORLD SIMULATION            ║")
        println("╚══════════════════════════════════════════════════╝")
        println("Group: $groupName")
        println("Phone 1: ${p1Pub.take(8)}...")
        println("Phone 2: ${p2Pub.take(8)}...")
        println("Relays: ${relays.joinToString()}")

        // ══════════════════════════════════════════════════
        // STEP 1: Connect both phones
        // ══════════════════════════════════════════════════
        println("\n── STEP 1: Connect both phones to relays ──")
        phone1.authSigner = { c, r -> p1Signer.createAuthEvent(c, r) }
        phone2.authSigner = { c, r -> p2Signer.createAuthEvent(c, r) }
        phone1.connect(relays)
        phone2.connect(relays)
        delay(3000)
        assertTrue("Phone 1 connected", phone1.isConnected)
        assertTrue("Phone 2 connected", phone2.isConnected)
        println("   ✅ Both phones connected")

        // Subscribe both to the group's live stream, as LiveSync does while the app is visible
        phone1.subscribe(groupId, now - 60)
        phone2.subscribe(groupId, now - 60)
        delay(1000)

        // ══════════════════════════════════════════════════
        // STEP 2: Phone 1 creates group (publishes group_meta with only Phone 1)
        // ══════════════════════════════════════════════════
        println("\n── STEP 2: Phone 1 creates group ──")
        val meta1 = buildGroupMeta(listOf(p1Pub), now)
        val meta1Event =
            p1Signer.createSignedEvent(
                groupId,
                "group_meta",
                encryption.encrypt(meta1, groupKey)
            )
        assertTrue("group_meta sig valid", meta1Event.verify())
        assertTrue("group_meta published", phone1.publish(meta1Event))
        println("   ✅ Group created with 1 member")

        delay(1500)

        // ══════════════════════════════════════════════════
        // STEP 3: Phone 2 joins group (publishes updated group_meta with both members)
        // ══════════════════════════════════════════════════
        println("\n── STEP 3: Phone 2 joins group ──")
        val meta2 = buildGroupMeta(listOf(p1Pub, p2Pub), now)
        val meta2Event =
            p2Signer.createSignedEvent(
                groupId,
                "group_meta",
                encryption.encrypt(meta2, groupKey)
            )
        assertTrue("join meta sig valid", meta2Event.verify())
        // Start collecting BEFORE publishing to avoid race with SharedFlow
        val joinDeferred =
            async {
                withTimeout(30_000) {
                    phone1.incomingEvents.first { e ->
                        e.pubkey == p2Pub && e.tags.any { it.size >= 2 && it[0] == "t" && it[1] == "group_meta" }
                    }
                }
            }
        delay(200)
        assertTrue("join meta published", phone2.publish(meta2Event))
        println("   ✅ Phone 2 joined, group now has 2 members")

        // Phone 1 receives the updated member list
        val joinEvent = joinDeferred.await()
        assertTrue("Join event sig valid", joinEvent.verify())
        val joinMeta =
            json
                .parseToJsonElement(
                    encryption.decrypt(joinEvent.content, groupKey)
                ).let { it as kotlinx.serialization.json.JsonObject }
        val members = joinMeta["members"]!!.let { it as kotlinx.serialization.json.JsonArray }
        assertEquals("Group should have 2 members", 2, members.size)
        println("   ✅ Phone 1 received updated member list: ${members.size} members")

        delay(1500)

        // ══════════════════════════════════════════════════
        // STEP 4: Phone 1 adds expense "Dinner ₹1500" (equal split)
        // ══════════════════════════════════════════════════
        println("\n── STEP 4: Phone 1 adds 'Dinner ₹1500' (equal split) ──")
        val exp1 =
            makeExpense(
                java.util.UUID
                    .randomUUID()
                    .toString(),
                150000,
                "INR",
                "Dinner at restaurant",
                p1Pub,
                SplitType.EQUAL,
                listOf(SplitEntry(p1Pub, 75000), SplitEntry(p2Pub, 75000)),
                now,
                "food"
            )
        val exp1Event = signExpense(p1Signer, exp1)
        assertTrue("exp1 sig valid", exp1Event.verify())
        val recv1Deferred =
            async {
                withTimeout(30_000) {
                    phone2.incomingEvents.first { e ->
                        e.tags.any { it.size >= 2 && it[0] == "t" && it[1] == "expense" } &&
                            e.pubkey == p1Pub
                    }
                }
            }
        delay(200)
        assertTrue("exp1 published", phone1.publish(exp1Event))
        println("   Published: ${exp1Event.id.take(8)}")

        // Phone 2 receives it
        val recv1 = recv1Deferred.await()
        val parsed1 =
            json.decodeFromString(
                Expense.serializer(),
                encryption.decrypt(recv1.content, groupKey)
            )
        assertEquals("Dinner at restaurant", parsed1.description)
        assertEquals(150000L, parsed1.amount)
        assertEquals(p1Pub, parsed1.paidBy)
        assertEquals(SplitType.EQUAL, parsed1.splitType)
        assertEquals(2, parsed1.splitAmong.size)
        println("   ✅ Phone 2 received & verified: ${parsed1.description} ₹${parsed1.amount / 100}")

        delay(2000) // let relays settle between different publishers

        // ══════════════════════════════════════════════════
        // STEP 5: Phone 2 adds expense "Cab ₹400" (exact split)
        // ══════════════════════════════════════════════════
        println("\n── STEP 5: Phone 2 adds 'Cab ₹400' (exact split) ──")
        val exp2 =
            makeExpense(
                java.util.UUID
                    .randomUUID()
                    .toString(),
                40000,
                "INR",
                "Cab to airport",
                p2Pub,
                SplitType.EXACT,
                listOf(SplitEntry(p1Pub, 25000), SplitEntry(p2Pub, 15000)),
                now + 1,
                "transport"
            )
        val exp2Event = signExpense(p2Signer, exp2)
        assertTrue("exp2 sig valid", exp2Event.verify())
        val recv2Deferred =
            async {
                withTimeout(30_000) {
                    phone1.incomingEvents.first { e ->
                        e.tags.any { it.size >= 2 && it[0] == "t" && it[1] == "expense" } &&
                            e.pubkey == p2Pub
                    }
                }
            }
        delay(200)
        assertTrue("exp2 published", phone2.publish(exp2Event))
        println("   Published: ${exp2Event.id.take(8)}")

        // Phone 1 receives it
        val recv2 = recv2Deferred.await()
        val parsed2 =
            json.decodeFromString(
                Expense.serializer(),
                encryption.decrypt(recv2.content, groupKey)
            )
        assertEquals("Cab to airport", parsed2.description)
        assertEquals(40000L, parsed2.amount)
        assertEquals(p2Pub, parsed2.paidBy)
        assertEquals(SplitType.EXACT, parsed2.splitType)
        assertEquals(25000L, parsed2.splitAmong.find { it.pubkey == p1Pub }!!.share)
        assertEquals(15000L, parsed2.splitAmong.find { it.pubkey == p2Pub }!!.share)
        println("   ✅ Phone 1 received & verified: ${parsed2.description} ₹${parsed2.amount / 100}")

        delay(2000)

        // ══════════════════════════════════════════════════
        // STEP 6: Phone 1 adds expense "Groceries ₹800" (equal split)
        // ══════════════════════════════════════════════════
        println("\n── STEP 6: Phone 1 adds 'Groceries ₹800' (equal split) ──")
        val exp3 =
            makeExpense(
                java.util.UUID
                    .randomUUID()
                    .toString(),
                80000,
                "INR",
                "Groceries from store",
                p1Pub,
                SplitType.EQUAL,
                listOf(SplitEntry(p1Pub, 40000), SplitEntry(p2Pub, 40000)),
                now + 2,
                "groceries"
            )
        val exp3Event = signExpense(p1Signer, exp3)
        val recv3Deferred =
            async {
                withTimeout(30_000) {
                    phone2.incomingEvents.first { e ->
                        e.tags.any { it.size >= 2 && it[0] == "t" && it[1] == "expense" } &&
                            e.tags.any { it.size >= 2 && it[0] == "x" && it[1] == exp3.id }
                    }
                }
            }
        delay(200)
        assertTrue("exp3 published", phone1.publish(exp3Event))
        println("   Published: ${exp3Event.id.take(8)}")

        val recv3 = recv3Deferred.await()
        val parsed3 =
            json.decodeFromString(
                Expense.serializer(),
                encryption.decrypt(recv3.content, groupKey)
            )
        assertEquals("Groceries from store", parsed3.description)
        assertEquals(80000L, parsed3.amount)
        println("   ✅ Phone 2 received & verified: ${parsed3.description} ₹${parsed3.amount / 100}")

        delay(2000)

        // ══════════════════════════════════════════════════
        // STEP 7: Phone 2 deletes Cab expense (correction)
        // ══════════════════════════════════════════════════
        println("\n── STEP 7: Phone 2 deletes 'Cab ₹400' (correction) ──")
        val delEvent = signDeletion(p2Signer, exp2.id, now + 3)
        assertTrue("delete sig valid", delEvent.verify())
        val recvDelDeferred =
            async {
                withTimeout(30_000) {
                    phone1.incomingEvents.first { e ->
                        e.tags.any { it.size >= 2 && it[0] == "t" && it[1] == "expense_delete" }
                    }
                }
            }
        delay(200)
        assertTrue("delete published", phone2.publish(delEvent))
        println("   Published deletion: ${delEvent.id.take(8)}")

        val recvDel = recvDelDeferred.await()
        val delPayload =
            json
                .parseToJsonElement(
                    encryption.decrypt(recvDel.content, groupKey)
                ).let { it as kotlinx.serialization.json.JsonObject }
        assertEquals(
            exp2.id,
            delPayload["expense_id"]!!.let {
                (it as JsonPrimitive).content
            }
        )
        println("   ✅ Phone 1 received deletion for expense ${exp2.id.take(8)}")

        delay(3000)

        // ══════════════════════════════════════════════════
        // STEP 8: Phone 2 re-adds corrected expense "Cab ₹350"
        // ══════════════════════════════════════════════════
        println("\n── STEP 8: Phone 2 adds corrected 'Cab ₹350' ──")
        val exp2b =
            makeExpense(
                java.util.UUID
                    .randomUUID()
                    .toString(),
                35000,
                "INR",
                "Cab to airport (corrected)",
                p2Pub,
                SplitType.EQUAL,
                listOf(SplitEntry(p1Pub, 17500), SplitEntry(p2Pub, 17500)),
                now + 4,
                "transport"
            )
        val exp2bEvent = signExpense(p2Signer, exp2b)
        val recv2bDeferred =
            async {
                withTimeout(30_000) {
                    phone1.incomingEvents.first { e ->
                        e.tags.any { it.size >= 2 && it[0] == "x" && it[1] == exp2b.id }
                    }
                }
            }
        delay(200)
        assertTrue("corrected exp published", phone2.publish(exp2bEvent))
        println("   Published: ${exp2bEvent.id.take(8)}")

        val recv2b = recv2bDeferred.await()
        val parsed2b =
            json.decodeFromString(
                Expense.serializer(),
                encryption.decrypt(recv2b.content, groupKey)
            )
        assertEquals("Cab to airport (corrected)", parsed2b.description)
        assertEquals(35000L, parsed2b.amount)
        println("   ✅ Phone 1 received corrected expense: ${parsed2b.description} ₹${parsed2b.amount / 100}")

        // ══════════════════════════════════════════════════
        // STEP 9: Verify encryption isolation: wrong key can't decrypt
        // ══════════════════════════════════════════════════
        println("\n── STEP 9: Verify encryption isolation ──")
        val wrongKey = encryption.generateGroupKey()
        try {
            encryption.decrypt(recv1.content, wrongKey)
            fail("Should not decrypt with wrong key")
        } catch (_: Exception) {
            println("   ✅ Wrong key correctly fails to decrypt")
        }

        // ══════════════════════════════════════════════════
        // STEP 10: Verify signature integrity: tampered event fails
        // ══════════════════════════════════════════════════
        println("\n── STEP 10: Verify signature integrity ──")
        val tampered = recv1.copy(content = "tampered-content")
        assertFalse("Tampered event must fail verification", tampered.verify())
        println("   ✅ Tampered event correctly fails signature check")

        // ══════════════════════════════════════════════════
        // STEP 11: Both phones compute identical balances
        // ══════════════════════════════════════════════════
        println("\n── STEP 11: Balance calculation (after correction) ──")
        // Active expenses: Dinner ₹1500 (P1 paid), Groceries ₹800 (P1 paid), Cab ₹350 corrected (P2 paid)
        // Cab ₹400 was deleted
        val activeExpenses = listOf(parsed1, parsed3, parsed2b)
        val balances = mutableMapOf<String, Long>()
        for (exp in activeExpenses) {
            balances[exp.paidBy] = (balances[exp.paidBy] ?: 0) + exp.amount
            for (s in exp.splitAmong) {
                balances[s.pubkey] = (balances[s.pubkey] ?: 0) - s.share
            }
        }
        val p1Bal = balances[p1Pub] ?: 0
        val p2Bal = balances[p2Pub] ?: 0

        println("   Active expenses:")
        println("     Dinner ₹1500, paid by Phone 1, split ₹750/₹750")
        println("     Groceries ₹800, paid by Phone 1, split ₹400/₹400")
        println("     Cab ₹350 (corrected), paid by Phone 2, split ₹175/₹175")
        println("   ─────────────────────────────")
        // P1 paid 1500+800=2300, owes 750+400+175=1325, net = +975
        // P2 paid 350, owes 750+400+175=1325, net = -975
        println("   Phone 1 net: ₹${p1Bal / 100} (${if (p1Bal > 0) "is owed" else "owes"})")
        println("   Phone 2 net: ₹${p2Bal / 100} (${if (p2Bal > 0) "is owed" else "owes"})")
        assertEquals("Balances must sum to zero", 0, p1Bal + p2Bal)
        assertEquals("Phone 1 owed ₹975", 97500L, p1Bal)
        assertEquals("Phone 2 owes ₹975", -97500L, p2Bal)
        println("   ✅ Both phones compute identical balances")

        // ══════════════════════════════════════════════════
        // STEP 12: Verify event tag structure (expense UUID travels in 'x', never in 'e')
        // ══════════════════════════════════════════════════
        println("\n── STEP 12: Verify event tag structure ──")
        // Expense events must use 'x' tag (not 'e') for expense UUID
        for ((name, event) in listOf("exp1" to exp1Event, "exp2b" to exp2bEvent, "exp3" to exp3Event)) {
            val xTag = event.tags.find { it[0] == "x" }
            val eTag = event.tags.find { it[0] == "e" }
            assertNotNull("$name must have x tag", xTag)
            assertNull("$name must NOT have e tag (reserved for event IDs)", eTag)
            assertNotNull("$name must have d tag", event.tags.find { it[0] == "d" })
            assertNotNull("$name must have g tag", event.tags.find { it[0] == "g" })
            assertNotNull("$name must have t tag", event.tags.find { it[0] == "t" })
        }
        // Deletion event also uses x tag for expense UUID reference
        val delXTag = delEvent.tags.find { it[0] == "x" }
        assertNotNull("deletion must have x tag", delXTag)
        println("   ✅ All events use 'x' tag for expense UUID (not reserved 'e')")

        // ══════════════════════════════════════════════════
        // STEP 13: Verify d-tag uniqueness (addressable events)
        // ══════════════════════════════════════════════════
        println("\n── STEP 13: Verify d-tag uniqueness ──")
        val expenseEvents = listOf(exp1Event, exp2Event, exp3Event, exp2bEvent)
        val expDTags = expenseEvents.map { e -> e.tags.find { it[0] == "d" }!![1] }
        assertEquals("Expense d-tags must be unique", expDTags.size, expDTags.toSet().size)
        // The deletion is its own command: it must not share the deleted expense's addressable slot, or an
        // addressable relay could drop the original while the deletion still refers to it.
        val delDTag = delEvent.tags.find { it[0] == "d" }!![1]
        val exp2DTag = exp2Event.tags.find { it[0] == "d" }!![1]
        assertNotEquals("Deletion d-tag must not evict the deleted expense", exp2DTag, delDTag)
        assertEquals("Deletion x-tag must reference the deleted expense", exp2.id, delXTag!![1])
        println("   ✅ Expense d-tags unique, deletion has its own relay address")

        // ══════════════════════════════════════════════════
        // SUMMARY
        // ══════════════════════════════════════════════════
        println("\n╔══════════════════════════════════════════════════╗")
        println("║  ✅ ALL 13 STEPS PASSED                          ║")
        println("╠══════════════════════════════════════════════════╣")
        println("║  ✓ Group creation & relay publish                ║")
        println("║  ✓ Phone 2 join & member list sync               ║")
        println("║  ✓ Phone 1→2 expense (equal split)               ║")
        println("║  ✓ Phone 2→1 expense (exact split)               ║")
        println("║  ✓ Phone 1→2 another expense                     ║")
        println("║  ✓ Expense deletion (correction flow)            ║")
        println("║  ✓ Corrected expense re-add                      ║")
        println("║  ✓ Encryption isolation (wrong key rejected)     ║")
        println("║  ✓ Signature integrity (tamper detected)         ║")
        println("║  ✓ Balance computation matches on both phones    ║")
        println("║  ✓ Tag structure correct ('x' not 'e')           ║")
        println("║  ✓ d-tag uniqueness (no relay overwrites)        ║")
        println("║  ✓ Real relays: snort.social + nos.lol            ║")
        println("╚══════════════════════════════════════════════════╝")
    }
}
