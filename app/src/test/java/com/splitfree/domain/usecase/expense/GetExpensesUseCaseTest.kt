package com.splitfree.domain.usecase.expense

import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.ExpenseIdentity
import com.splitfree.domain.model.expense.SplitEntry
import com.splitfree.domain.model.expense.SplitType
import com.splitfree.domain.repository.EventRepositoryContract
import com.splitfree.domain.repository.EventSnapshot
import com.splitfree.domain.repository.GroupRepositoryContract
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GetExpensesUseCaseTest {
    private val eventRepo = mockk<EventRepositoryContract>()
    private val groupRepo = mockk<GroupRepositoryContract>()
    private val encryption = mockk<GroupEncryption>()
    private lateinit var useCase: GetExpensesUseCase

    private val groupId = "g1"
    private val groupKey = "key1"
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setup() {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, any()) } returns groupKey
        useCase = GetExpensesUseCase(eventRepo, groupRepo, encryption)
    }

    @Test
    fun `observe returns empty when no group key`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns null
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, any()) } returns null
        every { eventRepo.observeEventsByGroup(groupId) } returns flowOf(listOf(makeEntity("e1")))

        val result = useCase.observe(groupId).first()
        assertTrue(result.isEmpty())
    }

    @Test
    fun `observe filters only expense events with uuid`() = runBlocking {
        val expense = makeExpense("exp1")
        val expenseJson = json.encodeToString(Expense.serializer(), expense)
        every { encryption.decrypt("enc1", groupKey) } returns expenseJson
        every { encryption.decrypt("enc2", groupKey) } returns """{"data":"x"}"""

        every { eventRepo.observeEventsByGroup(groupId) } returns flowOf(
            listOf(
                makeEntity("e1", eventType = "expense", uuid = "exp1", content = "enc1"),
                makeEntity("e2", eventType = "settlement", uuid = "s1", content = "enc2"),
                makeEntity("e3", eventType = "expense", uuid = null, content = "enc1")
            )
        )

        val result = useCase.observe(groupId).first()
        assertEquals(1, result.size)
        assertEquals("exp1", result[0].id)
    }

    @Test
    fun `observe skips events that fail decryption`() = runBlocking {
        every { encryption.decrypt(any(), groupKey) } throws RuntimeException("bad key")
        every { eventRepo.observeEventsByGroup(groupId) } returns flowOf(
            listOf(makeEntity("e1", eventType = "expense", uuid = "exp1"))
        )

        val result = useCase.observe(groupId).first()
        assertTrue(result.isEmpty())
    }

    @Test
    fun `observe sorts by timestamp descending`() = runBlocking {
        val old = makeExpense("exp1", timestamp = 100)
        val recent = makeExpense("exp2", timestamp = 200)
        every { encryption.decrypt("enc1", groupKey) } returns json.encodeToString(Expense.serializer(), old)
        every { encryption.decrypt("enc2", groupKey) } returns json.encodeToString(Expense.serializer(), recent)

        every { eventRepo.observeEventsByGroup(groupId) } returns flowOf(
            listOf(
                makeEntity("e1", eventType = "expense", uuid = "exp1", content = "enc1"),
                makeEntity("e2", eventType = "expense", uuid = "exp2", content = "enc2")
            )
        )

        val result = useCase.observe(groupId).first()
        assertEquals("exp2", result[0].id)
        assertEquals("exp1", result[1].id)
    }

    // --- Corrections and deletions ---

    @Test
    fun `corrected expense shows the corrected payload in place of the original`() = runBlocking {
        val original = makeExpense("exp1", amount = 5000, timestamp = 100)
        val corrected = makeExpense("exp1", amount = 7000, timestamp = 100, description = "fixed")
        every { encryption.decrypt("enc-orig", groupKey) } returns json.encodeToString(Expense.serializer(), original)
        every { encryption.decrypt("enc-corr", groupKey) } returns json.encodeToString(Expense.serializer(), corrected)
        every { eventRepo.observeEventsByGroup(groupId) } returns flowOf(
            listOf(
                makeEntity("e1", eventType = "expense", uuid = "exp1", content = "enc-orig", createdAt = 1000),
                makeEntity(
                    "e2",
                    eventType = "expense_correction",
                    uuid = "exp1",
                    content = "enc-corr",
                    createdAt = 2000
                )
            )
        )

        val result = useCase.observe(groupId).first()

        assertEquals(1, result.size)
        assertEquals("exp1", result[0].id)
        assertEquals(7000L, result[0].amount)
        assertEquals("fixed", result[0].description)
    }

    @Test
    fun `latest correction by createdAt wins regardless of storage order`() = runBlocking {
        val original = makeExpense("exp1", amount = 5000)
        val c1 = makeExpense("exp1", amount = 6000, description = "first fix")
        val c2 = makeExpense("exp1", amount = 7000, description = "second fix")
        every { encryption.decrypt("enc-orig", groupKey) } returns json.encodeToString(Expense.serializer(), original)
        every { encryption.decrypt("enc-c1", groupKey) } returns json.encodeToString(Expense.serializer(), c1)
        every { encryption.decrypt("enc-c2", groupKey) } returns json.encodeToString(Expense.serializer(), c2)
        every { eventRepo.observeEventsByGroup(groupId) } returns flowOf(
            listOf(
                makeEntity("e1", eventType = "expense", uuid = "exp1", content = "enc-orig", createdAt = 1000),
                // Newer correction stored before the older one.
                makeEntity("e3", eventType = "expense_correction", uuid = "exp1", content = "enc-c2", createdAt = 3000),
                makeEntity("e2", eventType = "expense_correction", uuid = "exp1", content = "enc-c1", createdAt = 2000)
            )
        )

        val result = useCase.observe(groupId).first()

        assertEquals(1, result.size)
        assertEquals(7000L, result[0].amount)
        assertEquals("second fix", result[0].description)
    }

    @Test
    fun `corrected expense keeps the original's ordering position`() = runBlocking {
        // Original at t=100 gets corrected with a payload dated t=999; another expense sits at t=200.
        val original = makeExpense("exp1", timestamp = 100)
        val corrected = makeExpense("exp1", amount = 1, timestamp = 999)
        val other = makeExpense("exp2", timestamp = 200)
        every { encryption.decrypt("enc-orig", groupKey) } returns json.encodeToString(Expense.serializer(), original)
        every { encryption.decrypt("enc-corr", groupKey) } returns json.encodeToString(Expense.serializer(), corrected)
        every { encryption.decrypt("enc-other", groupKey) } returns json.encodeToString(Expense.serializer(), other)
        every { eventRepo.observeEventsByGroup(groupId) } returns flowOf(
            listOf(
                makeEntity("e1", eventType = "expense", uuid = "exp1", content = "enc-orig", createdAt = 1000),
                makeEntity("e2", eventType = "expense", uuid = "exp2", content = "enc-other", createdAt = 1500),
                makeEntity(
                    "e3",
                    eventType = "expense_correction",
                    uuid = "exp1",
                    content = "enc-corr",
                    createdAt = 2000
                )
            )
        )

        val result = useCase.observe(groupId).first()

        assertEquals(listOf("exp2", "exp1"), result.map { it.id })
        assertEquals(1L, result[1].amount) // corrected payload shown
    }

    @Test
    fun `deleted expense is hidden`() = runBlocking {
        val kept = makeExpense("exp1")
        val gone = makeExpense("exp2")
        every { encryption.decrypt("enc1", groupKey) } returns json.encodeToString(Expense.serializer(), kept)
        every { encryption.decrypt("enc2", groupKey) } returns json.encodeToString(Expense.serializer(), gone)
        every { encryption.decrypt("enc-del", groupKey) } returns """{"reason":"dup"}"""
        every { eventRepo.observeEventsByGroup(groupId) } returns flowOf(
            listOf(
                makeEntity("e1", eventType = "expense", uuid = "exp1", content = "enc1"),
                makeEntity("e2", eventType = "expense", uuid = "exp2", content = "enc2"),
                makeEntity("e3", eventType = "expense_delete", uuid = "exp2", content = "enc-del", createdAt = 2000)
            )
        )

        val result = useCase.observe(groupId).first()

        assertEquals(listOf("exp1"), result.map { it.id })
    }

    @Test
    fun `deleted expense stays hidden even if it was corrected`() = runBlocking {
        val original = makeExpense("exp1")
        val corrected = makeExpense("exp1", amount = 1)
        every { encryption.decrypt("enc-orig", groupKey) } returns json.encodeToString(Expense.serializer(), original)
        every { encryption.decrypt("enc-corr", groupKey) } returns json.encodeToString(Expense.serializer(), corrected)
        every { encryption.decrypt("enc-del", groupKey) } returns "{}"
        every { eventRepo.observeEventsByGroup(groupId) } returns flowOf(
            listOf(
                makeEntity("e1", eventType = "expense", uuid = "exp1", content = "enc-orig", createdAt = 1000),
                makeEntity(
                    "e2",
                    eventType = "expense_correction",
                    uuid = "exp1",
                    content = "enc-corr",
                    createdAt = 2000
                ),
                makeEntity("e3", eventType = "expense_delete", uuid = "exp1", content = "enc-del", createdAt = 3000)
            )
        )

        val result = useCase.observe(groupId).first()

        assertTrue(result.isEmpty())
    }

    @Test
    fun `duplicate expense events with the same uuid collapse to the earliest`() = runBlocking {
        val first = makeExpense("exp1", amount = 100)
        val dup = makeExpense("exp1", amount = 999)
        every { encryption.decrypt("enc-first", groupKey) } returns json.encodeToString(Expense.serializer(), first)
        every { encryption.decrypt("enc-dup", groupKey) } returns json.encodeToString(Expense.serializer(), dup)
        every { eventRepo.observeEventsByGroup(groupId) } returns flowOf(
            listOf(
                makeEntity("e2", eventType = "expense", uuid = "exp1", content = "enc-dup", createdAt = 2000),
                makeEntity("e1", eventType = "expense", uuid = "exp1", content = "enc-first", createdAt = 1000)
            )
        )

        val result = useCase.observe(groupId).first()

        assertEquals(1, result.size)
        assertEquals(100L, result[0].amount)
    }

    @Test
    fun `correction whose original is missing locally is still shown`() = runBlocking {
        val corrected = makeExpense("exp1", amount = 7000)
        every { encryption.decrypt("enc-corr", groupKey) } returns json.encodeToString(Expense.serializer(), corrected)
        every { eventRepo.observeEventsByGroup(groupId) } returns flowOf(
            listOf(
                makeEntity(
                    "e2",
                    eventType = "expense_correction",
                    uuid = "exp1",
                    content = "enc-corr",
                    createdAt = 2000
                )
            )
        )

        val result = useCase.observe(groupId).first()

        assertEquals(listOf("exp1"), result.map { it.id })
        assertEquals(7000L, result[0].amount)
    }

    // --- Authors ---

    @Test
    fun `observeWithAuthors pairs each expense with the pubkey of its original event`() = runBlocking {
        val mine = makeExpense("exp1")
        val theirs = makeExpense("exp2", timestamp = 500)
        every { encryption.decrypt("enc1", groupKey) } returns json.encodeToString(Expense.serializer(), mine)
        every { encryption.decrypt("enc2", groupKey) } returns json.encodeToString(Expense.serializer(), theirs)
        every { eventRepo.observeEventsByGroup(groupId) } returns flowOf(
            listOf(
                makeEntity("e1", eventType = "expense", uuid = "exp1", content = "enc1", pubkey = "pub1"),
                makeEntity("e2", eventType = "expense", uuid = "exp2", content = "enc2", pubkey = "pub2")
            )
        )

        val result = useCase.observeWithAuthors(groupId).first()

        assertEquals(listOf("exp1" to "pub1", "exp2" to "pub2"), result.map { it.expense.id to it.authorPubkey })
    }

    @Test
    fun `someone else's correction never replaces the original's payload or author`() = runBlocking {
        val original = makeExpense("exp1", amount = 5000)
        val corrected = makeExpense("exp1", amount = 7000)
        every { encryption.decrypt("enc-orig", groupKey) } returns json.encodeToString(Expense.serializer(), original)
        every { encryption.decrypt("enc-corr", groupKey) } returns json.encodeToString(Expense.serializer(), corrected)
        every { eventRepo.observeEventsByGroup(groupId) } returns flowOf(
            listOf(
                makeEntity("e1", eventType = "expense", uuid = "exp1", content = "enc-orig", pubkey = "pub1"),
                makeEntity(
                    "e2",
                    eventType = "expense_correction",
                    uuid = "exp1",
                    content = "enc-corr",
                    createdAt = 2000,
                    pubkey = "pub2"
                )
            )
        )

        val result = useCase.observeWithAuthors(groupId).first()

        // pub1's record is untouched; pub2's correction is bound to pub2's (missing) record and shown as its own.
        val pub1 = result.single { it.authorPubkey == "pub1" }
        assertEquals(5000L, pub1.expense.amount)
        val pub2 = result.single { it.authorPubkey == "pub2" }
        assertEquals(7000L, pub2.expense.amount)
    }

    @Test
    fun `correction without a local original is attributed to the correction's signer`() = runBlocking {
        val corrected = makeExpense("exp1", amount = 7000)
        every { encryption.decrypt("enc-corr", groupKey) } returns json.encodeToString(Expense.serializer(), corrected)
        every { eventRepo.observeEventsByGroup(groupId) } returns flowOf(
            listOf(
                makeEntity(
                    "e2",
                    eventType = "expense_correction",
                    uuid = "exp1",
                    content = "enc-corr",
                    createdAt = 2000,
                    pubkey = "pub2"
                )
            )
        )

        val result = useCase.observeWithAuthors(groupId).first()

        assertEquals("pub2", result.single().authorPubkey)
    }

    // --- Author-bound expense identity ---

    private val alice = "alice"
    private val mallory = "mallory"

    private fun stubPayload(enc: String, expense: Expense) {
        every { encryption.decrypt(enc, groupKey) } returns json.encodeToString(Expense.serializer(), expense)
    }

    /** One `U` event by [pubkey]; [enc] selects the stubbed plaintext. */
    private fun uEvent(id: String, type: String, enc: String, createdAt: Long, pubkey: String) =
        makeEntity(id, eventType = type, uuid = "U", content = enc, createdAt = createdAt, pubkey = pubkey)

    private fun aliceExpense() = uEvent("e_alice", "expense", "enc-alice", createdAt = 100, pubkey = alice)

    /** Alice's expense U (t=100) and Mallory's front-run of the same uuid with an earlier created_at. */
    private fun collisionEvents(): List<EventSnapshot> {
        stubPayload("enc-alice", makeExpense("U", amount = 100, timestamp = 100, description = "alice's"))
        stubPayload("enc-mallory", makeExpense("U", amount = 200, timestamp = 10, description = "mallory's"))
        stubPayload("enc-mallory-fix", makeExpense("U", amount = 400, timestamp = 10, description = "mallory fixed"))
        every { encryption.decrypt("enc-del", groupKey) } returns "{}"
        return listOf(aliceExpense(), uEvent("e_mal", "expense", "enc-mallory", createdAt = 10, pubkey = mallory))
    }

    private fun malloryCorrection() =
        uEvent("c_mal", "expense_correction", "enc-mallory-fix", createdAt = 20, pubkey = mallory)

    private fun malloryDelete() = uEvent("d_mal", "expense_delete", "enc-del", createdAt = 30, pubkey = mallory)

    private fun aliceDelete() = uEvent("d_alice", "expense_delete", "enc-del", createdAt = 300, pubkey = alice)

    private fun <T> permutations(items: List<T>): List<List<T>> {
        if (items.size <= 1) return listOf(items)
        return items.indices.flatMap { i ->
            permutations(items.take(i) + items.drop(i + 1)).map { rest -> listOf(items[i]) + rest }
        }
    }

    /** Observes once per permutation of [events] and asserts the visible list is identical every time. */
    private suspend fun observeForEveryOrder(events: List<EventSnapshot>): List<AuthoredExpense> {
        val results = permutations(events).map { order ->
            every { eventRepo.observeEventsByGroup(groupId) } returns flowOf(order)
            useCase.observeWithAuthors(groupId).first()
        }
        for (r in results) assertEquals("list must not depend on arrival order", results.first(), r)
        return results.first()
    }

    @Test
    fun `hostile collision - Mallory's correction of a shared uuid only changes Mallory's record`() = runBlocking {
        val shown = observeForEveryOrder(collisionEvents() + malloryCorrection())

        assertEquals(2, shown.size)
        val aliceRecord = shown.single { it.authorPubkey == alice }
        assertEquals(100L, aliceRecord.expense.amount)
        assertEquals("alice's", aliceRecord.expense.description)
        val malloryRecord = shown.single { it.authorPubkey == mallory }
        assertEquals(400L, malloryRecord.expense.amount)
        assertEquals("mallory fixed", malloryRecord.expense.description)
        // Alice's newer timestamp sorts first; the order is deterministic.
        assertEquals(listOf(alice, mallory), shown.map { it.authorPubkey })
    }

    @Test
    fun `hostile collision - Mallory's delete of a shared uuid hides only Mallory's record`() = runBlocking {
        val shown = observeForEveryOrder(collisionEvents() + malloryDelete())

        assertEquals(1, shown.size)
        assertEquals(alice, shown.single().authorPubkey)
        assertEquals(100L, shown.single().expense.amount)
    }

    @Test
    fun `hostile collision - correction then delete by Mallory leaves Alice's record as-is`() = runBlocking {
        val shown = observeForEveryOrder(collisionEvents() + malloryCorrection() + malloryDelete())

        assertEquals(listOf(alice to 100L), shown.map { it.authorPubkey to it.expense.amount })
    }

    @Test
    fun `hostile collision - Alice deleting hers still shows Mallory's separate record`() = runBlocking {
        val shown = observeForEveryOrder(collisionEvents() + aliceDelete())

        assertEquals(listOf(mallory to 200L), shown.map { it.authorPubkey to it.expense.amount })
    }

    @Test
    fun `correction by the original author still replaces the payload`() = runBlocking {
        collisionEvents()
        stubPayload("enc-alice-fix", makeExpense("U", amount = 150, timestamp = 100, description = "alice fixed"))
        val events = listOf(
            aliceExpense(),
            uEvent("c_alice", "expense_correction", "enc-alice-fix", createdAt = 200, pubkey = alice)
        )

        val shown = observeForEveryOrder(events)

        assertEquals(1, shown.size)
        assertEquals(150L, shown.single().expense.amount)
        assertEquals(alice, shown.single().authorPubkey)
    }

    @Test
    fun `delete by the original author still hides the expense`() = runBlocking {
        val events = collisionEvents().filter { it.pubkey == alice } + aliceDelete()

        assertTrue(observeForEveryOrder(events).isEmpty())
    }

    @Test
    fun `get resolves each exact identity regardless of colliding list order`() = runBlocking {
        for (events in permutations(collisionEvents())) {
            every { eventRepo.observeEventsByGroup(groupId) } returns flowOf(events)

            val first = useCase.get(groupId, ExpenseIdentity(alice, "U"))
            val second = useCase.get(groupId, ExpenseIdentity(mallory, "U"))

            assertEquals("alice's", first?.expense?.description)
            assertEquals("mallory's", second?.expense?.description)
            assertEquals(ExpenseIdentity(alice, "U"), first?.identity)
            assertEquals(ExpenseIdentity(mallory, "U"), second?.identity)
            assertNull(useCase.get(groupId, ExpenseIdentity("unknown", "U")))
        }
    }

    @Test
    fun `get uses the signed tag uuid even when the original payload id differs`() = runBlocking {
        stubPayload("enc-alice", makeExpense("payload-id"))
        every { eventRepo.observeEventsByGroup(groupId) } returns flowOf(listOf(aliceExpense()))

        val found = useCase.get(groupId, ExpenseIdentity(alice, "U"))

        assertEquals(ExpenseIdentity(alice, "U"), found?.identity)
        assertNull(useCase.get(groupId, ExpenseIdentity(alice, "payload-id")))
    }

    @Test
    fun `get never falls back to another author when the selected record is deleted`() = runBlocking {
        every { eventRepo.observeEventsByGroup(groupId) } returns flowOf(collisionEvents() + malloryDelete())

        assertNull(useCase.get(groupId, ExpenseIdentity(mallory, "U")))
        assertEquals(alice, useCase.get(groupId, ExpenseIdentity(alice, "U"))?.authorPubkey)
    }

    private fun makeExpense(id: String, timestamp: Long = 1000, amount: Long = 5000, description: String = "test") =
        Expense(
            id = id,
            amount = amount,
            currency = "INR",
            description = description,
            paidBy = "pub1",
            splitType = SplitType.EQUAL,
            splitAmong = listOf(SplitEntry("pub1", amount / 2), SplitEntry("pub2", amount - amount / 2)),
            timestamp = timestamp
        )

    private fun makeEntity(
        id: String,
        eventType: String = "expense",
        uuid: String? = "uuid",
        content: String = "enc",
        createdAt: Long = 1000,
        pubkey: String = "pub1"
    ) = EventSnapshot(
        eventId = id, groupId = groupId, pubkey = pubkey, createdAt = createdAt,
        kind = 30078, contentEncrypted = content, eventType = eventType,
        expenseUuid = uuid, sig = "sig", receivedAt = 1000
    )
}
