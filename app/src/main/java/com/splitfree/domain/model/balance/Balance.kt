package com.splitfree.domain.model.balance

/**
 * Net balance for a single member in a single currency.
 *
 * @property pubkey member's Nostr public key
 * @property net positive = owed money by others, negative = owes money to others
 * @property currency ISO 4217 currency code
 */
data class Balance(val pubkey: String, val net: Long, val currency: String = "INR")
