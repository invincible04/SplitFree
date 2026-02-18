package com.splitfree.domain.usecase.expense

import com.splitfree.domain.model.balance.Balance
import com.splitfree.domain.model.expense.DebtTransaction
import java.util.PriorityQueue
import javax.inject.Inject

/**
 * Reduces member balances to the minimum number of debtor→creditor transfers using a greedy algorithm.
 */
class SimplifyDebtsUseCase
@Inject
constructor() {
    /**
     * Reduce a list of balances to the minimum number of transfers using a greedy algorithm.
     * Groups by currency before simplifying.
     *
     * @param balances per-member net balances (positive = owed money, negative = owes money)
     * @return minimal list of debtor→creditor transactions that settle all debts
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
