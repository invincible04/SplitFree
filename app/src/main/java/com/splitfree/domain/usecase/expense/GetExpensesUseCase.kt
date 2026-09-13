package com.splitfree.domain.usecase.expense

import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.ExpenseIdentity
import com.splitfree.domain.repository.EventRepositoryContract
import com.splitfree.domain.repository.EventSnapshot
import com.splitfree.domain.repository.GroupRepositoryContract
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * One visible expense plus the pubkey that signed its original event. Only that key may correct or
 * delete the expense, so the UI needs it alongside the payload.
 */
data class AuthoredExpense(val expense: Expense, val authorPubkey: String)

/**
 * Observes and decrypts expenses for a group as a reactive [Flow].
 * Supports epoch-based key rotation: each event is decrypted with its epoch's key.
 *
 * The emitted list reflects the same rules as balance computation. Expenses are identified by
 * [ExpenseIdentity] `(author, uuid)`: a soft-delete or correction only applies to the original signed by
 * the same pubkey, so two authors sharing a UUID are two separate, both visible, expenses. A corrected
 * expense is shown with its latest correction's payload (latest by `createdAt`, then `eventId`), and
 * duplicate `expense` events from one author sharing an `x` tag collapse to the earliest one.
 */
class GetExpensesUseCase
@Inject
constructor(
    private val eventRepo: EventRepositoryContract,
    private val groupRepo: GroupRepositoryContract,
    private val encryption: GroupEncryption
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Observe decrypted expenses for a group, sorted newest-first by the original expense's timestamp.
     * A corrected expense keeps its original position but carries the corrected payload (same `id`).
     *
     * @param groupId target group UUID
     * @return reactive [Flow] of expenses; emits empty list if group key is unavailable
     */
    fun observe(groupId: String): Flow<List<Expense>> =
        observeWithAuthors(groupId).map { authored -> authored.map { it.expense } }

    /**
     * The current payload and author of one visible expense, or null when it is unknown or deleted.
     *
     * Expense identity is `(author, uuid)`, so two members may legitimately hold the same uuid. When
     * [preferAuthor] is given, that author's entry is returned if it exists; otherwise the first in
     * the deterministic list order.
     */
    suspend fun get(groupId: String, expenseId: String, preferAuthor: String? = null): AuthoredExpense? {
        val matches = observeWithAuthors(groupId).first().filter { it.expense.id == expenseId }
        return matches.firstOrNull { preferAuthor != null && it.authorPubkey == preferAuthor } ?: matches.firstOrNull()
    }

    /**
     * Same list as [observe], each entry paired with the pubkey of its original `expense` event. When
     * only a correction is known locally, the correction's author stands in (it is the same key: a
     * correction is bound to its author's own record).
     *
     * Output order is fully deterministic (timestamp desc, then id, then author) so the list does not
     * depend on the order events arrived in.
     */
    fun observeWithAuthors(groupId: String): Flow<List<AuthoredExpense>> =
        eventRepo.observeEventsByGroup(groupId).map { events ->
            // Build a cache of epoch -> key to avoid repeated lookups
            val keyCache = mutableMapOf<Int, String?>()
            suspend fun decryptExpense(e: EventSnapshot): Expense? {
                val key = keyCache.getOrPut(e.keyEpoch) {
                    groupRepo.getGroupKeyForEpoch(groupId, e.keyEpoch)
                } ?: return null
                val content = try {
                    encryption.decrypt(e.contentEncrypted, key)
                } catch (_: Exception) {
                    null
                }
                return content?.let { runCatching { json.decodeFromString<Expense>(it) }.getOrNull() }
            }

            val deleted = mutableSetOf<ExpenseIdentity>()
            val latestCorrection = mutableMapOf<ExpenseIdentity, EventSnapshot>()
            val earliestOriginal = mutableMapOf<ExpenseIdentity, EventSnapshot>()
            for (e in events) {
                val uuid = e.expenseUuid ?: continue
                val identity = ExpenseIdentity(e.pubkey, uuid)
                when (e.eventType) {
                    "expense_delete" -> deleted.add(identity)
                    "expense_correction" -> {
                        val current = latestCorrection[identity]
                        if (current == null || EventSnapshot.CANONICAL_ORDER.compare(e, current) > 0) {
                            latestCorrection[identity] = e
                        }
                    }

                    "expense" -> {
                        val current = earliestOriginal[identity]
                        if (current == null || EventSnapshot.CANONICAL_ORDER.compare(e, current) < 0) {
                            earliestOriginal[identity] = e
                        }
                    }
                }
            }

            (earliestOriginal.keys + latestCorrection.keys)
                .filter { it !in deleted }
                .mapNotNull { identity ->
                    val originalEvent = earliestOriginal[identity]
                    val correctionEvent = latestCorrection[identity]
                    val original = originalEvent?.let { decryptExpense(it) }
                    val corrected = correctionEvent?.let { decryptExpense(it) }
                    // Prefer the correction; fall back to the original if the correction is unreadable.
                    val shown = corrected?.copy(id = identity.expenseUuid) ?: original ?: return@mapNotNull null
                    AuthoredExpense(shown, identity.authorPubkey) to (original ?: shown).timestamp
                }
                .sortedWith(
                    compareByDescending<Pair<AuthoredExpense, Long>> { (_, sortTimestamp) -> sortTimestamp }
                        .thenBy { (authored, _) -> authored.expense.id }
                        .thenBy { (authored, _) -> authored.authorPubkey }
                )
                .map { (authored, _) -> authored }
        }.flowOn(Dispatchers.Default)
}
