package com.splitfree.domain.model

data class Balance(
    val pubkey: String,
    val net: Long, // positive = owed money, negative = owes money
    val currency: String = "INR",
)

data class DebtTransaction(
    val from: String, // pubkey of debtor
    val to: String, // pubkey of creditor
    val amount: Long,
    val currency: String = "INR",
)
