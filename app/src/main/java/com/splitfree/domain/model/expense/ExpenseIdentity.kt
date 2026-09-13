package com.splitfree.domain.model.expense

/**
 * Logical identity of one expense inside a group: the pubkey that signed the original `expense` event
 * plus the UUID it carries in its `x` tag.
 *
 * A UUID alone is not an identity. Any member can publish an `expense` reusing someone else's UUID, so
 * every projection (balances, expense list, delete/correct lookups) keys on `(author, uuid)`. An
 * `expense_correction` or `expense_delete` only ever affects the record of the *same* author; two authors
 * sharing a UUID own two separate expenses that are both preserved.
 *
 * @property authorPubkey hex pubkey of the original event's signer
 * @property expenseUuid the `x` tag / payload id
 */
data class ExpenseIdentity(val authorPubkey: String, val expenseUuid: String) {
    override fun toString(): String = "$expenseUuid@${authorPubkey.take(8)}"
}
