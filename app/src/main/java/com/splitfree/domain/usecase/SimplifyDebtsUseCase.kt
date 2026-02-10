package com.splitfree.domain.usecase

import com.splitfree.domain.model.Balance
import com.splitfree.domain.model.DebtTransaction
import java.util.PriorityQueue
import javax.inject.Inject

class SimplifyDebtsUseCase @Inject constructor() {

    operator fun invoke(balances: List<Balance>): List<DebtTransaction> {
        return balances.groupBy { it.currency }.flatMap { (currency, currencyBalances) ->
            simplifyForCurrency(currencyBalances, currency)
        }
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
            val (creditor, credit) = creditors.poll()
            val (debtor, debt) = debtors.poll()
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
