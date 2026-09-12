package com.splitfree.ui.screens.groupdetail

import com.splitfree.domain.model.expense.DebtTransaction
import com.splitfree.domain.model.expense.Expense

/**
 * Pure, per-currency arithmetic behind the group screen. Balances are never summed across currencies: every
 * helper here takes the currency the user is looking at and ignores the rest.
 */

/** Every currency that appears in the group's simplified debts or its expenses, sorted for a stable menu. */
internal fun groupCurrencies(debts: List<DebtTransaction>, expenses: List<Expense>): List<String> =
    (debts.asSequence().map { it.currency } + expenses.asSequence().map { it.currency })
        .distinct()
        .sorted()
        .toList()

/**
 * The currency the screen opens on: the one with the most open debts that involve [myPubkey]; ties fall back
 * to alphabetical order. `null` when the group has no currencies yet.
 */
internal fun defaultBalanceCurrency(currencies: List<String>, debts: List<DebtTransaction>, myPubkey: String): String? =
    currencies.maxWithOrNull(
        compareBy<String> { currency -> debts.count { it.currency == currency && it.involves(myPubkey) } }
            .thenByDescending { it }
    )

internal fun DebtTransaction.involves(pubkey: String): Boolean = from == pubkey || to == pubkey

/** A (from, to, currency) triple identifies one simplified debt; duplicates would otherwise crash keyed lists. */
internal fun debtKey(debt: DebtTransaction): String = "${debt.from}:${debt.to}:${debt.currency}"

/** Σ debts owed to me − Σ debts I owe, in [currency]; positive means the group owes me. */
internal fun myNetBalance(debts: List<DebtTransaction>, currency: String, myPubkey: String): Long =
    debts.filter { it.currency == currency }.sumOf { debt ->
        when (myPubkey) {
            debt.to -> debt.amount
            debt.from -> -debt.amount
            else -> 0L
        }
    }

/** Signed amount for one debt row: positive when [myPubkey] is the creditor, negative when the debtor, else 0. */
internal fun DebtTransaction.signedFor(myPubkey: String): Long = when (myPubkey) {
    to -> amount
    from -> -amount
    else -> 0L
}

/** The share [pubkey] carries in this expense, in minor units (0 when not a participant). */
internal fun Expense.shareOf(pubkey: String): Long = splitAmong.filter { it.pubkey == pubkey }.sumOf { it.share }

/** What one expense did to my balance, for the "you lent / your share" line under its amount. */
internal sealed interface PersonalDelta {
    /** I paid and others owe me [amount] (total minus my own share). */
    data class Lent(val amount: Long) : PersonalDelta

    /** Someone else paid; I owe them [amount]. */
    data class Share(val amount: Long) : PersonalDelta

    /** I paid, but only for myself. */
    data object NoChange : PersonalDelta

    /** I neither paid nor participated. */
    data object NotInvolved : PersonalDelta
}

internal fun Expense.personalDelta(myPubkey: String): PersonalDelta {
    val myShare = shareOf(myPubkey)
    return when {
        paidBy == myPubkey -> (amount - myShare).let { if (it > 0) PersonalDelta.Lent(it) else PersonalDelta.NoChange }
        myShare > 0 -> PersonalDelta.Share(myShare)
        else -> PersonalDelta.NotInvolved
    }
}

/** The one-line footnote under the summary amount. Never claims "settled" before any data has arrived. */
internal sealed interface SummaryNote {
    /** Nothing recorded at all (also the safe copy while the ledger is still loading). */
    data object NoExpenses : SummaryNote

    /** Expenses exist in this currency but no debt is open. */
    data class SettledIn(val currency: String) : SummaryNote

    /** No expense has been recorded in this currency. */
    data class NoCurrencyExpenses(val currency: String) : SummaryNote

    /** My own balance is zero, yet other members still owe each other. */
    data object OthersOpen : SummaryNote

    /** Regular case: how many expenses in this currency the balance is built from. */
    data class Across(val expenseCount: Int) : SummaryNote
}

internal fun summaryNote(
    debts: List<DebtTransaction>,
    expenses: List<Expense>,
    currency: String?,
    myPubkey: String
): SummaryNote {
    if (currency == null || (expenses.isEmpty() && debts.isEmpty())) return SummaryNote.NoExpenses
    val currencyDebts = debts.filter { it.currency == currency }
    val currencyExpenses = expenses.filter { it.currency == currency }
    return when {
        currencyDebts.isEmpty() ->
            if (currencyExpenses.isNotEmpty()) {
                SummaryNote.SettledIn(
                    currency
                )
            } else {
                SummaryNote.NoCurrencyExpenses(currency)
            }
        myNetBalance(currencyDebts, currency, myPubkey) == 0L -> SummaryNote.OthersOpen
        else -> SummaryNote.Across(currencyExpenses.size)
    }
}
