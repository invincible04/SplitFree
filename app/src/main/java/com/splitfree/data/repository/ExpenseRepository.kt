package com.splitfree.data.repository

import com.splitfree.data.identity.IdentityManager
import com.splitfree.data.local.dao.EventDao
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.Settlement
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.ExpenseSaveConflictException
import com.splitfree.sync.event.EventPublisher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Encrypts expense data and publishes it as Nostr events to the group's relay set.
 * Handles add, settle, delete, and correct operations.
 *
 * All operations follow the same pattern: serialize → encrypt with group key →
 * sign as kind-30078 → publish via [EventPublisher].
 */
@Singleton
class ExpenseRepository
@Inject
constructor(
    private val eventDao: EventDao,
    private val groupRepo: GroupRepository,
    private val encryption: GroupEncryption,
    private val identity: IdentityManager,
    private val signer: EventSigner,
    private val eventPublisher: EventPublisher
) : com.splitfree.domain.repository.ExpenseRepositoryContract {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val MAX_AMOUNT = 1_000_000_000_000L
        private const val MAX_DELETE_REASON_CHARS = 200
    }

    override suspend fun addExpense(expense: Expense, groupId: String, expectedAuthorPubkey: String?) {
        val author = checkedAuthor(expectedAuthorPubkey)
        validateExpenseShape(expense)
        val saved = getSavedExpense(groupId, expense.id, author)
        if (saved != null) {
            requireSameExpense(saved, expense)
            return
        }
        val group = groupRepo.getById(groupId) ?: error("Group $groupId not found")
        checkedAuthor(author)
        require(author in group.members) { "You are no longer a member of this group" }
        validateExpensePayload(expense, group)
        val groupKey = groupRepo.getGroupKeyForEpoch(groupId, group.keyEpoch) ?: error("Group key not found")
        val plaintext = json.encodeToString(Expense.serializer(), expense)
        val encrypted = encryption.encrypt(plaintext, groupKey)
        val event =
            signer.createSignedEvent(
                groupId = groupId,
                eventType = "expense",
                encryptedContent = encrypted,
                expenseUuid = expense.id
            )
        checkedAuthor(author)
        check(event.pubkey == author) { "Identity changed while saving" }
        if (!eventPublisher.publishExpense(event, group, expense.id)) {
            requireSameExpense(checkNotNull(getSavedExpense(groupId, expense.id, author)), expense)
        }
    }

    override suspend fun getSavedExpense(groupId: String, expenseId: String, expectedAuthorPubkey: String?): Expense? {
        val author = checkedAuthor(expectedAuthorPubkey)
        val event = eventDao.getExpenseByAuthor(expenseId, groupId, author)
        checkedAuthor(author)
        if (event == null) return null
        val key = groupRepo.getGroupKeyForEpoch(groupId, event.keyEpoch) ?: error("Saved expense key not found")
        return json.decodeFromString(Expense.serializer(), encryption.decrypt(event.contentEncrypted, key)).also {
            checkedAuthor(author)
            check(it.id == expenseId) { "Saved expense ID does not match its event" }
        }
    }

    private fun checkedAuthor(expectedAuthorPubkey: String?): String {
        val author = identity.getPublicKeyHex()
        check(author.isNotBlank() && (expectedAuthorPubkey == null || author == expectedAuthorPubkey)) {
            "Your identity changed. Close this draft and start a new expense"
        }
        return author
    }

    private fun requireSameExpense(saved: Expense, requested: Expense) {
        if (saved.copy(splitAmong = saved.splitAmong.sortedBy { it.pubkey }) !=
            requested.copy(splitAmong = requested.splitAmong.sortedBy { it.pubkey })
        ) {
            throw ExpenseSaveConflictException()
        }
    }

    /** Structural checks that do not depend on group state (safe to run before any I/O). */
    private fun validateExpenseShape(expense: Expense) {
        require(expense.id.isNotBlank()) { "Expense ID must not be blank" }
        require(expense.amount > 0) { "Expense amount must be positive" }
        require(expense.amount <= MAX_AMOUNT) { "Expense amount exceeds maximum" }
        require(expense.splitAmong.isNotEmpty()) { "Expense must have at least one split" }
        require(expense.splitAmong.all { it.share > 0 }) { "Split shares must be positive" }
        require(expense.splitAmong.map { it.pubkey }.distinct().size == expense.splitAmong.size) {
            "Split participants must be unique"
        }
        require(expense.splitAmong.fold(0L) { total, entry -> Math.addExact(total, entry.share) } == expense.amount) {
            "Split shares must equal the expense amount"
        }
    }

    /** Full payload validation: structural checks plus membership checks against [group]. */
    private fun validateExpensePayload(expense: Expense, group: Group) {
        validateExpenseShape(expense)
        require(expense.paidBy in group.members) { "Payer is no longer a member of this group" }
        require(expense.splitAmong.all { it.pubkey in group.members }) {
            "Split participants must be current group members"
        }
    }

    override suspend fun addSettlement(settlement: Settlement, groupId: String) {
        val myPubkey = identity.getPublicKeyHex()
        require(settlement.from == myPubkey || settlement.to == myPubkey) {
            "You can only record settlements you're involved in"
        }
        require(settlement.amount > 0) { "Settlement amount must be positive" }
        require(settlement.amount <= MAX_AMOUNT) { "Settlement amount exceeds maximum" }

        val group = groupRepo.getById(groupId) ?: error("Group $groupId not found")
        require(myPubkey in group.members) { "You are no longer a member of this group" }
        require(settlement.from in group.members) { "Payer is no longer a member of this group" }
        require(settlement.to in group.members) { "Recipient is no longer a member of this group" }
        // Encrypt with the loaded group's current epoch key only, never a stale/legacy key.
        val groupKey = groupRepo.getGroupKeyForEpoch(groupId, group.keyEpoch) ?: error("Group key not found")
        val plaintext = json.encodeToString(Settlement.serializer(), settlement)
        val encrypted = encryption.encrypt(plaintext, groupKey)
        val event =
            signer.createSignedEvent(
                groupId = groupId,
                eventType = "settlement",
                encryptedContent = encrypted,
                expenseUuid = settlement.id
            )
        eventPublisher.publishToGroup(event, groupId, encrypted, "settlement", settlement.id)
    }

    override suspend fun deleteExpense(expenseUuid: String, groupId: String, reason: String) {
        val original = eventDao.getExpenseByUuid(expenseUuid, groupId)
        checkNotNull(original) { "Expense $expenseUuid not found" }
        val author = identity.getPublicKeyHex()
        check(original.pubkey == author) { "Only the creator can delete this expense" }

        val group = groupRepo.getById(groupId) ?: error("Group $groupId not found")
        require(author in group.members) { "You are no longer a member of this group" }
        // Encrypt with the loaded group's current epoch key only, never a stale/legacy key.
        val groupKey = groupRepo.getGroupKeyForEpoch(groupId, group.keyEpoch) ?: error("Group key not found")
        val plaintext = json.encodeToString(
            MapSerializer(String.serializer(), String.serializer()),
            mapOf(
                "reason" to reason.take(MAX_DELETE_REASON_CHARS)
            )
        )
        val encrypted = encryption.encrypt(plaintext, groupKey)
        val event =
            signer.createSignedEvent(
                groupId = groupId,
                eventType = "expense_delete",
                encryptedContent = encrypted,
                expenseUuid = expenseUuid
            )
        eventPublisher.publishToGroup(event, groupId, encrypted, "expense_delete", expenseUuid)
    }

    override suspend fun correctExpense(originalUuid: String, corrected: Expense, groupId: String) {
        val original = eventDao.getExpenseByUuid(originalUuid, groupId)
        checkNotNull(original) { "Expense $originalUuid not found" }
        val author = identity.getPublicKeyHex()
        check(original.pubkey == author) { "Only the creator can correct this expense" }

        val group = groupRepo.getById(groupId) ?: error("Group $groupId not found")
        require(author in group.members) { "You are no longer a member of this group" }
        require(corrected.id == originalUuid) { "Corrected expense must keep the original expense ID" }
        validateExpensePayload(corrected, group)
        // Encrypt with the loaded group's current epoch key only, never a stale/legacy key.
        val groupKey = groupRepo.getGroupKeyForEpoch(groupId, group.keyEpoch) ?: error("Group key not found")
        val plaintext = json.encodeToString(Expense.serializer(), corrected)
        val encrypted = encryption.encrypt(plaintext, groupKey)
        val event =
            signer.createSignedEvent(
                groupId = groupId,
                eventType = "expense_correction",
                encryptedContent = encrypted,
                expenseUuid = originalUuid
            )
        eventPublisher.publishToGroup(event, groupId, encrypted, "expense_correction", originalUuid)
    }
}
