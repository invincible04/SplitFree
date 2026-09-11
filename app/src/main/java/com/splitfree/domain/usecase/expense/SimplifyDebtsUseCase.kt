package com.splitfree.domain.usecase.expense

import com.splitfree.domain.model.balance.Balance
import com.splitfree.domain.model.expense.DebtTransaction
import java.util.PriorityQueue
import javax.inject.Inject

/**
 * Reduces member balances to a small set of debtor→creditor transfers using a greedy
 * largest-creditor / largest-debtor matching.
 *
 * Every transfer fully settles at least one party, so the result never has more than `n − 1`
 * transfers for `n` members with a non-zero balance. It is not guaranteed to be the minimum:
 * finding the fewest transfers is NP-hard (subset-sum), and the greedy choice can miss cases where
 * a group of smaller balances cancels exactly.
 */
class SimplifyDebtsUseCase
@Inject
constructor() {
    /**
     * Reduce a list of balances to at most `n − 1` transfers per currency using the greedy
     * matching described on the class. Groups by currency before simplifying.
     *
     * @param balances per-member net balances (positive = owed money, negative = owes money)
     * @return debtor→creditor transactions that settle all debts; few, but not necessarily minimal
     */
    operator fun invoke(balances: List<Balance>): List<DebtTransaction> =
        balances.groupBy { it.currency }.flatMap { (currency, currencyBalances) ->
            simplifyForCurrency(currencyBalances, currency)
        }

    private fun simplifyForCurrency(balances: List<Balance>, currency: String): List<DebtTransaction> {
        val creditors = PriorityQueue<Pair<String, Long>>(compareByDescending { it.second })
        val debtors = PriorityQueue<Pair<String, Long>>(compareByDescending { it.second })

        for (b in balances) {
            when {
                b.net > 0 -> creditors.add(b.pubkey to b.net)
                b.net < 0 -> debtors.add(b.pubkey to -b.net)
            }
        }

        val transactions = mutableListOf<DebtTransaction>()
        while (creditors.isNotEmpty() && debtors.isNotEmpty()) {
            val (creditor, credit) = creditors.poll() ?: break
            val (debtor, debt) = debtors.poll() ?: break
            val transfer = minOf(credit, debt)
            transactions.add(DebtTransaction(from = debtor, to = creditor, amount = transfer, currency = currency))
            val remainingCredit = credit - transfer
            val remainingDebt = debt - transfer
            if (remainingCredit > 0) creditors.add(creditor to remainingCredit)
            if (remainingDebt > 0) debtors.add(debtor to remainingDebt)
        }
        return transactions
    }
}
