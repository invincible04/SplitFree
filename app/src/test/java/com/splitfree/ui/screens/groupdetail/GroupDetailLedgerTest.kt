package com.splitfree.ui.screens.groupdetail

import com.splitfree.domain.model.expense.DebtTransaction
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.ExpenseIdentity
import com.splitfree.domain.model.expense.SplitEntry
import com.splitfree.domain.model.expense.SplitType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroupDetailLedgerTest {
    private val me = "me"
    private val a = "alice"
    private val b = "bob"

    private fun debt(from: String, to: String, amount: Long, currency: String = "INR") =
        DebtTransaction(from = from, to = to, amount = amount, currency = currency)

    private fun expense(id: String, amount: Long, paidBy: String, shares: Map<String, Long>, currency: String = "INR") =
        Expense(
            id = id,
            amount = amount,
            currency = currency,
            description = id,
            paidBy = paidBy,
            splitType = SplitType.EQUAL,
            splitAmong = shares.map { (key, share) -> SplitEntry(key, share) },
            timestamp = 1_700_000_000L
        )

    @Test
    fun `currencies are the sorted union of debt and expense currencies`() {
        val debts = listOf(debt(a, me, 100, "USD"), debt(b, me, 100, "INR"))
        val expenses = listOf(expense("e", 100, me, mapOf(me to 100), currency = "EUR"))
        assertEquals(listOf("EUR", "INR", "USD"), groupCurrencies(debts, expenses))
        assertEquals(emptyList<String>(), groupCurrencies(emptyList(), emptyList()))
    }

    @Test
    fun `default currency is the one with most debts involving me, ties alphabetical`() {
        val debts = listOf(debt(a, me, 1, "USD"), debt(me, b, 1, "USD"), debt(a, me, 1, "INR"), debt(a, b, 1, "INR"))
        assertEquals("USD", defaultBalanceCurrency(listOf("INR", "USD"), debts, me))
        // Only debts between others: every count is zero, so alphabetical wins.
        assertEquals("EUR", defaultBalanceCurrency(listOf("USD", "EUR"), listOf(debt(a, b, 1, "USD")), me))
        assertNull(defaultBalanceCurrency(emptyList(), debts, me))
    }

    @Test
    fun `net balance credits what I am owed and debits what I owe in one currency only`() {
        val debts =
            listOf(debt(a, me, 800), debt(b, me, 1600), debt(me, a, 300), debt(a, b, 500), debt(a, me, 999, "USD"))
        assertEquals(2100L, myNetBalance(debts, "INR", me))
        assertEquals(999L, myNetBalance(debts, "USD", me))
        assertEquals(0L, myNetBalance(debts, "EUR", me))
        assertEquals(800L, debt(a, me, 800).signedFor(me))
        assertEquals(-300L, debt(me, a, 300).signedFor(me))
        assertEquals(0L, debt(a, b, 500).signedFor(me))
    }

    @Test
    fun `personal delta distinguishes lent, share, no change and not involved`() {
        val shares = mapOf(me to 1000L, a to 1000L, b to 2000L)
        assertEquals(PersonalDelta.Lent(3000), expense("paid", 4000, me, shares).personalDelta(me))
        assertEquals(PersonalDelta.Share(1000), expense("owe", 4000, a, shares).personalDelta(me))
        assertEquals(PersonalDelta.NoChange, expense("self", 500, me, mapOf(me to 500L)).personalDelta(me))
        assertEquals(
            PersonalDelta.NotInvolved,
            expense("others", 500, a, mapOf(a to 250L, b to 250L)).personalDelta(me)
        )
    }

    @Test
    fun `summary note never says settled before any data exists`() {
        assertEquals(SummaryNote.NoExpenses, summaryNote(emptyList(), emptyList(), null, me))
        assertEquals(SummaryNote.NoExpenses, summaryNote(emptyList(), emptyList(), "INR", me))
    }

    @Test
    fun `summary note covers settled, other currency, others open and across`() {
        val inr = expense("e1", 100, me, mapOf(me to 50L, a to 50L))
        val usd = expense("e2", 100, a, mapOf(me to 50L, a to 50L), currency = "USD")
        assertEquals(SummaryNote.SettledIn("INR"), summaryNote(emptyList(), listOf(inr), "INR", me))
        assertEquals(SummaryNote.NoCurrencyExpenses("EUR"), summaryNote(emptyList(), listOf(inr), "EUR", me))
        assertEquals(SummaryNote.OthersOpen, summaryNote(listOf(debt(a, b, 50)), listOf(inr), "INR", me))
        assertEquals(
            SummaryNote.Across(2),
            summaryNote(listOf(debt(a, me, 50)), listOf(inr, inr.copy(id = "e3")), "INR", me)
        )
        assertEquals(SummaryNote.Across(1), summaryNote(listOf(debt(me, a, 50, "USD")), listOf(inr, usd), "USD", me))
    }

    @Test
    fun `sheet saver round-trips every sheet and drops null`() {
        val settle = GroupSheet.Settle(debt(a, me, 800))
        listOf(
            GroupSheet.Invite,
            GroupSheet.Tools,
            GroupSheet.SyncStatus,
            settle,
            GroupSheet.ExpenseDetail(ExpenseIdentity("alice", "e-1")),
            GroupSheet.RemoveMember(b)
        ).forEach { sheet ->
            @Suppress("UNCHECKED_CAST")
            val saved = with(GroupSheetSaver) { FakeSaverScope.save(sheet) } as List<String>
            assertEquals(sheet, GroupSheetSaver.restore(saved))
        }
        assertNull(with(GroupSheetSaver) { FakeSaverScope.save(null) })
    }

    @Test
    fun `expense sheet restoration keeps the full author and rejects old uuid-only state`() {
        val author = "same-prefix-" + "a".repeat(64)
        val sheet = GroupSheet.ExpenseDetail(ExpenseIdentity(author, "shared"))
        val saved = with(GroupSheetSaver) { FakeSaverScope.save(sheet) }

        assertEquals(sheet, GroupSheetSaver.restore(checkNotNull(saved)))
        assertNull(GroupSheetSaver.restore(listOf("expense", "shared")))
        assertNull(GroupSheetSaver.restore(listOf("expense", "", "shared")))
        assertNull(GroupSheetSaver.restore(listOf("expense", author, "")))
    }

    private object FakeSaverScope : androidx.compose.runtime.saveable.SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }
}
