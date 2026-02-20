package com.splitfree.data.repository

import com.splitfree.data.identity.IdentityManager
import com.splitfree.data.local.dao.EventDao
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.Settlement
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

    override suspend fun addExpense(expense: Expense, groupId: String) {
        require(expense.amount > 0) { "Expense amount must be positive" }
        require(expense.amount <= 1_000_000_000_000L) { "Expense amount exceeds maximum" }
        require(expense.splitAmong.isNotEmpty()) { "Expense must have at least one split" }
        require(expense.splitAmong.none { it.share < 0 }) { "Split shares must be non-negative" }
        val groupKey = groupRepo.getGroupKey(groupId) ?: error("Group $groupId not found")
        val plaintext = json.encodeToString(Expense.serializer(), expense)
        val encrypted = encryption.encrypt(plaintext, groupKey)
        val event =
            signer.createSignedEvent(
                groupId = groupId,
                eventType = "expense",
                encryptedContent = encrypted,
                expenseUuid = expense.id
            )
        eventPublisher.publishToGroup(event, groupId, encrypted, "expense", expense.id)
    }

    override suspend fun addSettlement(settlement: Settlement, groupId: String) {
        val myPubkey = identity.getPublicKeyHex()
        require(settlement.from == myPubkey || settlement.to == myPubkey) {
            "You can only record settlements you're involved in"
        }
        require(settlement.amount > 0) { "Settlement amount must be positive" }
        require(settlement.amount <= 1_000_000_000_000L) { "Settlement amount exceeds maximum" }

        val groupKey = groupRepo.getGroupKey(groupId) ?: error("Group $groupId not found")
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
        val original = eventDao.getExpenseByUuid(expenseUuid)
        checkNotNull(original) { "Expense $expenseUuid not found" }
        check(original.pubkey == identity.getPublicKeyHex()) { "Only the creator can delete this expense" }

        val groupKey = groupRepo.getGroupKey(groupId) ?: error("Group $groupId not found")
        val plaintext = json.encodeToString(
            MapSerializer(String.serializer(), String.serializer()),
            mapOf(
                "reason" to reason
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
        val original = eventDao.getExpenseByUuid(originalUuid)
        checkNotNull(original) { "Expense $originalUuid not found" }
        check(original.pubkey == identity.getPublicKeyHex()) { "Only the creator can correct this expense" }

        val groupKey = groupRepo.getGroupKey(groupId) ?: error("Group $groupId not found")
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
