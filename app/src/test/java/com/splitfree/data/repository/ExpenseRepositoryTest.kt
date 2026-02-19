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
import com.splitfree.sync.event.EventPublisher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ExpenseRepositoryTest {
    private val eventDao = mockk<EventDao>(relaxed = true)
    private val groupRepo = mockk<GroupRepository>(relaxed = true)
    private val encryption = mockk<GroupEncryption>(relaxed = true)
    private val identity = mockk<IdentityManager>(relaxed = true)
    private val signer = mockk<EventSigner>(relaxed = true)
    private val eventPublisher = mockk<EventPublisher>(relaxed = true)

    private fun repo() = ExpenseRepository(eventDao, groupRepo, encryption, identity, signer, eventPublisher)

    private val testEvent = NostrEvent("eid", "pub", 1, 30078, listOf(listOf("g", "g1")), "enc", "sig")

    init {
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
        coVerify { eventPublisher.publishToGroup(testEvent, "g1", "encrypted", "expense", "u1") }
    }

    @Test(expected = IllegalStateException::class)
    fun `addExpense throws when no group key`() = runTest {
        coEvery { groupRepo.getGroupKey("g1") } returns null
        repo().addExpense(
            Expense("u1", 100, "INR", "t", "a", SplitType.EQUAL, listOf(SplitEntry("a", 100)), 1),
            "g1"
        )
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
        coEvery { eventDao.getExpenseByUuid("u1") } returns
            EventEntity(
                "e1",
                "g1",
                "alice",
                1,
                30078,
                "enc",
                "dec",
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
        coEvery { eventDao.getExpenseByUuid("u1") } returns null
        repo().deleteExpense("u1", "g1")
    }

    @Test(expected = IllegalStateException::class)
    fun `deleteExpense throws when not creator`() = runTest {
        every { identity.getPublicKeyHex() } returns "bob"
        coEvery { eventDao.getExpenseByUuid("u1") } returns
            EventEntity(
                "e1",
                "g1",
                "alice",
                1,
                30078,
                "enc",
                "dec",
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
        coEvery { eventDao.getExpenseByUuid("u1") } returns
            EventEntity(
                "e1",
                "g1",
                "alice",
                1,
                30078,
                "enc",
                "dec",
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
        coEvery { eventDao.getExpenseByUuid("u1") } returns
            EventEntity(
                "e1",
                "g1",
                "alice",
                1,
                30078,
                "enc",
                "dec",
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
        coEvery { eventDao.getExpenseByUuid("u1") } returns
            EventEntity(
                "e1",
                "g1",
                "alice",
                1,
                30078,
                "enc",
                "dec",
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
        coEvery { eventDao.getExpenseByUuid("u1") } returns null
        repo().correctExpense("u1", mockk(), "g1")
    }
}
