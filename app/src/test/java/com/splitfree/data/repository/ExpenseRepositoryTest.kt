package com.splitfree.data.repository

import com.splitfree.data.identity.IdentityManager
import com.splitfree.data.local.dao.EventDao
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.Settlement
import com.splitfree.domain.model.expense.SplitEntry
import com.splitfree.domain.model.expense.SplitType
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.ExpenseSaveConflictException
import com.splitfree.sync.event.EventPublisher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpenseRepositoryTest {
    private val eventDao = mockk<EventDao>(relaxed = true)
    private val groupRepo = mockk<GroupRepository>(relaxed = true)
    private val encryption = mockk<GroupEncryption>(relaxed = true)
    private val identity = mockk<IdentityManager>(relaxed = true)
    private val signer = mockk<EventSigner>(relaxed = true)
    private val eventPublisher = mockk<EventPublisher>(relaxed = true)

    private fun repo() = ExpenseRepository(eventDao, groupRepo, encryption, identity, signer, eventPublisher)

    private val testEvent = NostrEvent("eid", "alice", 1, 30078, listOf(listOf("g", "g1")), "enc", "sig")

    private val group = Group(
        "g1",
        "Group",
        createdBy = "alice",
        createdAt = 1,
        members = listOf("alice", "bob"),
        relays = emptyList()
    )
    private val expense = Expense(
        "u1", 100, "INR", "test", "alice", SplitType.EQUAL,
        listOf(SplitEntry("alice", 50), SplitEntry("bob", 50)), 1, "food"
    )

    init {
        every { identity.getPublicKeyHex() } returns "alice"
        coEvery { groupRepo.getById("g1") } returns group
        coEvery { groupRepo.getGroupKeyForEpoch(any(), any()) } returns "testkey"
        coEvery { eventDao.getExpenseByAuthor(any(), any(), any()) } returns null
        coEvery { eventPublisher.publishExpense(any(), any(), any()) } returns true
        coEvery { groupRepo.getGroupKey(any()) } returns "testkey"
        every { encryption.encrypt(any(), any()) } returns "encrypted"
        every { signer.createSignedEvent(any(), any(), any(), any()) } returns testEvent
    }

    // --- addExpense ---

    @Test
    fun `addExpense encrypts and stores event`() = runTest {
        val expense =
            Expense(
                "u1",
                100,
                "INR",
                "test",
                "alice",
                SplitType.EQUAL,
                listOf(SplitEntry("alice", 50), SplitEntry("bob", 50)),
                1
            )
        repo().addExpense(expense, "g1")

        coVerify { encryption.encrypt(any(), "testkey") }
        coVerify { signer.createSignedEvent("g1", "expense", "encrypted", "u1") }
        coVerify { eventPublisher.publishExpense(testEvent, group, "u1") }
    }

    @Test(expected = IllegalStateException::class)
    fun `addExpense throws when no group key`() = runTest {
        coEvery { groupRepo.getGroupKeyForEpoch("g1", 0) } returns null
        repo().addExpense(expense, "g1")
    }

    // --- addSettlement ---

    @Test
    fun `addSettlement encrypts and stores`() = runTest {
        every { identity.getPublicKeyHex() } returns "bob"
        val settlement = Settlement("s1", "bob", "alice", 50, "INR", timestamp = 1)
        repo().addSettlement(settlement, "g1")

        coVerify { eventPublisher.publishToGroup(any(), "g1", any(), "settlement", "s1") }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `addSettlement rejects non-party`() = runTest {
        every { identity.getPublicKeyHex() } returns "charlie"
        repo().addSettlement(Settlement("s1", "bob", "alice", 50, "INR", timestamp = 1), "g1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `addSettlement rejects zero amount`() = runTest {
        every { identity.getPublicKeyHex() } returns "bob"
        repo().addSettlement(Settlement("s1", "bob", "alice", 0, "INR", timestamp = 1), "g1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `addSettlement rejects amount exceeding max`() = runTest {
        every { identity.getPublicKeyHex() } returns "bob"
        repo().addSettlement(Settlement("s1", "bob", "alice", 10_000_000_000_01L, "INR", timestamp = 1), "g1")
    }

    // --- deleteExpense ---

    @Test
    fun `deleteExpense stores delete event`() = runTest {
        every { identity.getPublicKeyHex() } returns "alice"
        coEvery { eventDao.getExpenseByUuid("u1", "g1") } returns
            EventEntity(
                "e1",
                "g1",
                "alice",
                1,
                30078,
                "enc",
                "expense",
                "u1",
                "sig",
                receivedAt = 1
            )
        repo().deleteExpense("u1", "g1")

        coVerify { signer.createSignedEvent("g1", "expense_delete", "encrypted", "u1") }
        coVerify { eventPublisher.publishToGroup(any(), "g1", any(), "expense_delete", "u1") }
    }

    @Test(expected = IllegalStateException::class)
    fun `deleteExpense throws when expense not found`() = runTest {
        coEvery { eventDao.getExpenseByUuid("u1", "g1") } returns null
        repo().deleteExpense("u1", "g1")
    }

    @Test(expected = IllegalStateException::class)
    fun `deleteExpense throws when not creator`() = runTest {
        every { identity.getPublicKeyHex() } returns "bob"
        coEvery { eventDao.getExpenseByUuid("u1", "g1") } returns
            EventEntity(
                "e1",
                "g1",
                "alice",
                1,
                30078,
                "enc",
                "expense",
                "u1",
                "sig",
                receivedAt = 1
            )
        repo().deleteExpense("u1", "g1")
    }

    // --- correctExpense ---

    @Test
    fun `correctExpense stores correction event`() = runTest {
        every { identity.getPublicKeyHex() } returns "alice"
        coEvery { eventDao.getExpenseByUuid("u1", "g1") } returns
            EventEntity(
                "e1",
                "g1",
                "alice",
                1,
                30078,
                "enc",
                "expense",
                "u1",
                "sig",
                receivedAt = 1
            )
        val corrected =
            Expense(
                "u1c",
                200,
                "INR",
                "corrected",
                "alice",
                SplitType.EQUAL,
                listOf(SplitEntry("alice", 100), SplitEntry("bob", 100)),
                2
            )
        repo().correctExpense("u1", corrected, "g1")

        coVerify { signer.createSignedEvent("g1", "expense_correction", "encrypted", "u1") }
    }

    @Test(expected = IllegalStateException::class)
    fun `correctExpense throws when not creator`() = runTest {
        every { identity.getPublicKeyHex() } returns "bob"
        coEvery { eventDao.getExpenseByUuid("u1", "g1") } returns
            EventEntity(
                "e1",
                "g1",
                "alice",
                1,
                30078,
                "enc",
                "expense",
                "u1",
                "sig",
                receivedAt = 1
            )
        repo().correctExpense("u1", mockk(), "g1")
    }

    @Test
    fun `addSettlement accepts when to is myPubkey`() = runTest {
        every { identity.getPublicKeyHex() } returns "alice"
        val settlement = Settlement("s1", "bob", "alice", 50, "INR", timestamp = 1)
        repo().addSettlement(settlement, "g1")
        coVerify { eventPublisher.publishToGroup(any(), "g1", any(), "settlement", "s1") }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `addSettlement rejects negative amount`() = runTest {
        every { identity.getPublicKeyHex() } returns "bob"
        repo().addSettlement(Settlement("s1", "bob", "alice", -1, "INR", timestamp = 1), "g1")
    }

    @Test(expected = IllegalStateException::class)
    fun `correctExpense throws when no group key`() = runTest {
        every { identity.getPublicKeyHex() } returns "alice"
        coEvery { eventDao.getExpenseByUuid("u1", "g1") } returns
            EventEntity(
                "e1",
                "g1",
                "alice",
                1,
                30078,
                "enc",
                "expense",
                "u1",
                "sig",
                receivedAt = 1
            )
        coEvery { groupRepo.getGroupKey("g1") } returns null
        repo().correctExpense("u1", mockk(), "g1")
    }

    @Test(expected = IllegalStateException::class)
    fun `correctExpense throws when expense not found`() = runTest {
        coEvery { eventDao.getExpenseByUuid("u1", "g1") } returns null
        repo().correctExpense("u1", mockk(), "g1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `addExpense rejects removed author`() = runTest {
        coEvery { groupRepo.getById("g1") } returns group.copy(members = listOf("bob"))
        repo().addExpense(expense, "g1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `addExpense rejects removed payer`() = runTest {
        repo().addExpense(expense.copy(paidBy = "outsider"), "g1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `addExpense rejects removed participant`() = runTest {
        repo().addExpense(expense.copy(splitAmong = listOf(SplitEntry("outsider", 100))), "g1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `addExpense rejects duplicate participants`() = runTest {
        repo().addExpense(expense.copy(splitAmong = listOf(SplitEntry("alice", 50), SplitEntry("alice", 50))), "g1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `addExpense rejects incorrect share total`() = runTest {
        repo().addExpense(expense.copy(amount = 101), "g1")
    }

    @Test
    fun `retry compares canonical payload without publishing`() = runTest {
        stubSavedExpense()
        repo().addExpense(expense.copy(splitAmong = expense.splitAmong.reversed()), "g1")
        coVerify(exactly = 0) { eventPublisher.publishExpense(any(), any(), any()) }
    }

    @Test(expected = ExpenseSaveConflictException::class)
    fun `same operation with changed payload fails explicitly`() = runTest {
        stubSavedExpense()
        repo().addExpense(expense.copy(description = "Changed"), "g1")
    }

    @Test
    fun `race loser compares saved payload`() = runTest {
        val saved = savedEvent()
        coEvery { eventDao.getExpenseByAuthor("u1", "g1", "alice") } returnsMany listOf(null, saved)
        every { encryption.decrypt("saved", "testkey") } returns Json.encodeToString(Expense.serializer(), expense)
        coEvery { eventPublisher.publishExpense(any(), any(), any()) } returns false
        repo().addExpense(expense, "g1")
        coVerify(exactly = 2) { eventDao.getExpenseByAuthor("u1", "g1", "alice") }
    }

    @Test
    fun `saved recovery uses original epoch and current author`() = runTest {
        stubSavedExpense()
        assertEquals(expense, repo().getSavedExpense("g1", "u1"))
        coVerify { groupRepo.getGroupKeyForEpoch("g1", 3) }
        every { identity.getPublicKeyHex() } returns "bob"
        assertNull(repo().getSavedExpense("g1", "u1"))
    }

    @Test
    fun `save rejects changed draft author before any persistence or encryption`() = runTest {
        every { identity.getPublicKeyHex() } returns "bob"
        val failure = runCatching { repo().addExpense(expense, "g1", expectedAuthorPubkey = "alice") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        coVerify(exactly = 0) { eventDao.getExpenseByAuthor(any(), any(), any()) }
        coVerify(exactly = 0) { eventPublisher.publishExpense(any(), any(), any()) }
        verify(exactly = 0) { encryption.encrypt(any(), any()) }
    }

    @Test
    fun `save pins caller author when identity changes during group lookup`() = runTest {
        coEvery { groupRepo.getById("g1") } coAnswers {
            every { identity.getPublicKeyHex() } returns "bob"
            group
        }
        val failure = runCatching { repo().addExpense(expense, "g1", expectedAuthorPubkey = "alice") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        coVerify(exactly = 0) { eventPublisher.publishExpense(any(), any(), any()) }
        verify(exactly = 0) { encryption.encrypt(any(), any()) }
    }

    @Test
    fun `save rejects identity change during signing even if signed event uses original author`() = runTest {
        every { signer.createSignedEvent(any(), any(), any(), any()) } answers {
            every { identity.getPublicKeyHex() } returns "bob"
            testEvent
        }
        val failure = runCatching { repo().addExpense(expense, "g1", expectedAuthorPubkey = "alice") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        coVerify(exactly = 0) { eventPublisher.publishExpense(any(), any(), any()) }
    }

    @Test
    fun `recovery rejects different current author rather than claiming not saved`() = runTest {
        every { identity.getPublicKeyHex() } returns "bob"
        val failure = runCatching { repo().getSavedExpense("g1", "u1", "alice") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        coVerify(exactly = 0) { eventDao.getExpenseByAuthor(any(), any(), any()) }
    }

    @Test
    fun `recovery rejects identity changed during empty lookup`() = runTest {
        coEvery { eventDao.getExpenseByAuthor("u1", "g1", "alice") } coAnswers {
            every { identity.getPublicKeyHex() } returns "bob"
            null
        }
        val failure = runCatching { repo().getSavedExpense("g1", "u1", "alice") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun `recovery rejects identity changed during decryption`() = runTest {
        stubSavedExpense()
        every { encryption.decrypt("saved", "testkey") } answers {
            every { identity.getPublicKeyHex() } returns "bob"
            Json.encodeToString(Expense.serializer(), expense)
        }
        val failure = runCatching { repo().getSavedExpense("g1", "u1", "alice") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    private fun savedEvent() = EventEntity(
        "saved-event", "g1", "alice", 1, 30078, "saved", "expense", "u1", "sig", 1,
        keyEpoch = 3
    )

    private fun stubSavedExpense() {
        coEvery { eventDao.getExpenseByAuthor("u1", "g1", "alice") } returns savedEvent()
        every { encryption.decrypt("saved", "testkey") } returns Json.encodeToString(Expense.serializer(), expense)
    }
}
