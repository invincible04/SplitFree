package com.splitfree.domain.usecase.expense

import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.model.expense.Expense
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
    fun `author stays the original signer even when someone else's correction is shown`() = runBlocking {
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

        assertEquals(7000L, result.single().expense.amount)
        assertEquals("pub1", result.single().authorPubkey)
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
