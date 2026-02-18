package com.splitfree.domain.usecase.expense

import com.splitfree.data.local.dao.EventDao
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.SplitEntry
import com.splitfree.domain.model.expense.SplitType
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
    private val eventDao = mockk<EventDao>()
    private val groupRepo = mockk<GroupRepositoryContract>()
    private val encryption = mockk<GroupEncryption>()
    private lateinit var useCase: GetExpensesUseCase

    private val groupId = "g1"
    private val groupKey = "key1"
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setup() {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        useCase = GetExpensesUseCase(eventDao, groupRepo, encryption)
    }

    @Test
    fun `observe returns empty when no group key`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns null
        every { eventDao.observeEventsByGroup(groupId) } returns flowOf(listOf(makeEntity("e1")))

        val result = useCase.observe(groupId).first()
        assertTrue(result.isEmpty())
    }

    @Test
    fun `observe filters only expense events with uuid`() = runBlocking {
        val expense = makeExpense("exp1")
        val expenseJson = json.encodeToString(Expense.serializer(), expense)
        every { encryption.decrypt("enc1", groupKey) } returns expenseJson
        every { encryption.decrypt("enc2", groupKey) } returns """{"data":"x"}"""

        every { eventDao.observeEventsByGroup(groupId) } returns flowOf(
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
        every { eventDao.observeEventsByGroup(groupId) } returns flowOf(
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

        every { eventDao.observeEventsByGroup(groupId) } returns flowOf(
            listOf(
                makeEntity("e1", eventType = "expense", uuid = "exp1", content = "enc1"),
                makeEntity("e2", eventType = "expense", uuid = "exp2", content = "enc2")
            )
        )

        val result = useCase.observe(groupId).first()
        assertEquals("exp2", result[0].id)
        assertEquals("exp1", result[1].id)
    }

    private fun makeExpense(id: String, timestamp: Long = 1000) = Expense(
        id = id,
        amount = 5000,
        currency = "INR",
        description = "test",
        paidBy = "pub1",
        splitType = SplitType.EQUAL,
        splitAmong = listOf(SplitEntry("pub1", 2500), SplitEntry("pub2", 2500)),
        timestamp = timestamp
    )

    private fun makeEntity(id: String, eventType: String = "expense", uuid: String? = "uuid", content: String = "enc") =
        EventEntity(
            eventId = id, groupId = groupId, pubkey = "pub1", createdAt = 1000,
            kind = 30078, contentEncrypted = content, eventType = eventType,
            expenseUuid = uuid, sig = "sig", receivedAt = 1000
        )
}
