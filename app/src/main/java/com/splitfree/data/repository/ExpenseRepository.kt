package com.splitfree.data.repository

import com.splitfree.data.local.dao.EventDao
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.ExpenseIdentity
import com.splitfree.domain.model.expense.Settlement
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.EditableExpense
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.ExpenseCorrectionCommand
import com.splitfree.domain.repository.ExpenseRepositoryContract
import com.splitfree.domain.repository.ExpenseRevisionConflictException
import com.splitfree.domain.repository.ExpenseSaveConflictException
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Encrypts expense data and publishes it as Nostr events to the group's relay set.
 * Handles add, settle, delete, and correct operations.
 *
 * All operations follow the same pattern: serialize, encrypt with group key,
 * sign as kind-30078, publish via [EventPublisherContract].
 *
 * Every mutation is a command with its own relay address. Corrections and deletions are admitted against
 * the author's current revision of the expense, and a correction retried under the same command id after
 * an interruption is reconciled with the saved one instead of being published twice.
 */
@Singleton
class ExpenseRepository
@Inject
constructor(
    private val eventDao: EventDao,
    private val groupRepo: GroupRepositoryContract,
    private val encryption: GroupEncryption,
    private val identity: IdentityContract,
    private val signer: EventSigner,
    private val eventPublisher: EventPublisherContract
) : ExpenseRepositoryContract {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val MAX_AMOUNT = 1_000_000_000_000L
        private const val MAX_DELETE_REASON_CHARS = 200
        private const val NOT_MINE = "Expense not found or not yours"
    }

    /**
     * The author is pinned once up front and re-verified exactly once more, immediately before the
     * publish. Every identity change in between is caught by that final check (the signed event's
     * pubkey is compared as well), so no intermediate re-checks are needed; each one would cost
     * a Keystore round-trip.
     */
    override suspend fun addExpense(expense: Expense, groupId: String, expectedAuthorPubkey: String?) {
        val author = checkedAuthor(expectedAuthorPubkey)
        validateExpenseShape(expense)
        val saved = loadSavedExpense(groupId, expense.id, author)
        if (saved != null) {
            requireSameExpense(saved, expense)
            return
        }
        val group = groupRepo.getById(groupId) ?: error("Group $groupId not found")
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
            requireSameExpense(checkNotNull(loadSavedExpense(groupId, expense.id, author)), expense)
        }
    }

    override suspend fun getSavedExpense(groupId: String, expenseId: String, expectedAuthorPubkey: String?): Expense? {
        val author = checkedAuthor(expectedAuthorPubkey)
        // Re-check once after the lookup + decrypt so an identity swap mid-way is reported, not
        // mistaken for "nothing saved" or for another identity's expense.
        return loadSavedExpense(groupId, expenseId, author).also { checkedAuthor(author) }
    }

    /** Load and decrypt the expense [author] saved under [expenseId] in [groupId], or null if none. */
    private suspend fun loadSavedExpense(groupId: String, expenseId: String, author: String): Expense? {
        val event = eventDao.getExpenseByAuthor(expenseId, groupId, author) ?: return null
        return decryptExpense(event).also {
            check(it.id == expenseId) { "Saved expense ID does not match its event" }
        }
    }

    private suspend fun decryptExpense(event: EventEntity): Expense {
        val key = groupRepo.getGroupKeyForEpoch(event.groupId, event.keyEpoch) ?: error("Saved expense key not found")
        return json.decodeFromString(Expense.serializer(), encryption.decrypt(event.contentEncrypted, key))
    }

    override suspend fun getEditableExpense(
        groupId: String,
        expenseId: String,
        expectedAuthorPubkey: String
    ): EditableExpense? {
        val author = checkedAuthor(expectedAuthorPubkey)
        val revision = currentRevision(groupId, expenseId, author) ?: return null.also { checkedAuthor(author) }
        val expense = decryptExpense(revision)
        check(expense.id == expenseId) { "Saved expense ID does not match its event" }
        checkedAuthor(author)
        return EditableExpense(expense, revision.eventId, author)
    }

    override suspend fun getSavedCorrection(
        groupId: String,
        expenseId: String,
        expectedAuthorPubkey: String,
        command: ExpenseCorrectionCommand
    ): Expense? {
        val author = checkedAuthor(expectedAuthorPubkey)
        val saved =
            ExpenseEventHistory.command(
                eventDao.getEventsByTypeAndAuthor(groupId, "expense_correction", author),
                groupId,
                author,
                "expense_correction",
                command.id
            ) ?: return null.also { checkedAuthor(author) }
        if (saved.expenseUuid != expenseId) throw ExpenseSaveConflictException()
        return decryptExpense(saved).also {
            if (it.id != expenseId) throw ExpenseSaveConflictException()
            checkedAuthor(author)
        }
    }

    /** The row carrying the current state of [author]'s expense [expenseId], or null if unknown or deleted. */
    private suspend fun currentRevision(groupId: String, expenseId: String, author: String): EventEntity? =
        ExpenseEventHistory.current(
            eventDao.getExpenseHistoryByAuthor(expenseId, groupId, author),
            ExpenseIdentity(author, expenseId)
        )

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
        val myPubkey = checkedAuthor(null)
        require(settlement.from == myPubkey || settlement.to == myPubkey) {
            "You can only record settlements you're involved in"
        }
        require(settlement.id.isNotBlank()) { "Settlement ID must not be blank" }
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
            signer.createSignedCommandEvent(
                groupId = groupId,
                eventType = "settlement",
                encryptedContent = encrypted,
                expenseUuid = settlement.id,
                commandId = settlement.id
            )
        checkedAuthor(myPubkey)
        check(event.pubkey == myPubkey) { "Identity changed while saving" }
        eventPublisher.publishMutation(event, group, "settlement", settlement.id, settlement.id)
    }

    override suspend fun deleteExpense(
        expenseUuid: String,
        groupId: String,
        reason: String,
        expectedAuthorPubkey: String
    ) {
        val author = checkedAuthor(expectedAuthorPubkey)
        // Author-bound identity: only my own record under this UUID can be deleted. Another member's
        // expense reusing the UUID is a separate record and is neither found here nor affected.
        val history = eventDao.getExpenseHistoryByAuthor(expenseUuid, groupId, author)
        val revision = ExpenseEventHistory.current(history, ExpenseIdentity(author, expenseUuid))
            ?: error(if (history.isEmpty()) NOT_MINE else "This expense was already deleted")

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
        val commandId = UUID.randomUUID().toString()
        val event =
            signer.createSignedCommandEvent(
                groupId = groupId,
                eventType = "expense_delete",
                encryptedContent = encrypted,
                expenseUuid = expenseUuid,
                commandId = commandId
            )
        checkedAuthor(author)
        check(event.pubkey == author) { "Identity changed while deleting" }
        eventPublisher.publishMutation(event, group, "expense_delete", expenseUuid, commandId, revision.eventId)
    }

    override suspend fun correctExpense(
        originalUuid: String,
        corrected: Expense,
        groupId: String,
        expectedAuthorPubkey: String,
        command: ExpenseCorrectionCommand?
    ) {
        val author = checkedAuthor(expectedAuthorPubkey)
        require(corrected.id == originalUuid) { "Corrected expense must keep the original expense ID" }
        validateExpenseShape(corrected)
        if (command != null) {
            require(command.id.isNotBlank() && command.expectedRevisionId.isNotBlank()) { "Invalid correction command" }
            getSavedCorrection(groupId, originalUuid, author, command)?.let {
                requireSameExpense(it, corrected)
                return
            }
        }
        // Author-bound identity: a correction is bound to my own record under this UUID (see deleteExpense).
        val identity = ExpenseIdentity(author, originalUuid)
        val history = eventDao.getExpenseHistoryByAuthor(originalUuid, groupId, author)
        val revision = if (command == null) {
            ExpenseEventHistory.current(history, identity) ?: error(NOT_MINE)
        } else {
            try {
                ExpenseEventHistory.requireRevision(history, identity, command.expectedRevisionId)
            } catch (e: ExpenseRevisionConflictException) {
                // A concurrent copy of this command may have committed since the first recovery lookup.
                getSavedCorrection(groupId, originalUuid, author, command)?.let {
                    requireSameExpense(it, corrected)
                    return
                }
                throw e
            }
        }
        val operation = command ?: ExpenseCorrectionCommand(UUID.randomUUID().toString(), revision.eventId)

        val group = groupRepo.getById(groupId) ?: error("Group $groupId not found")
        require(author in group.members) { "You are no longer a member of this group" }
        validateExpensePayload(corrected, group)
        // Encrypt with the loaded group's current epoch key only, never a stale/legacy key.
        val groupKey = groupRepo.getGroupKeyForEpoch(groupId, group.keyEpoch) ?: error("Group key not found")
        val plaintext = json.encodeToString(Expense.serializer(), corrected)
        val encrypted = encryption.encrypt(plaintext, groupKey)
        val event =
            signer.createSignedCommandEvent(
                groupId = groupId,
                eventType = "expense_correction",
                encryptedContent = encrypted,
                expenseUuid = originalUuid,
                commandId = operation.id,
                // A same-second edit sorts after the revision it replaces, not by a random event id.
                createdAt = maxOf(System.currentTimeMillis() / 1000, Math.addExact(revision.createdAt, 1L))
            )
        checkedAuthor(author)
        check(event.pubkey == author) { "Identity changed while correcting" }
        val saved = eventPublisher.publishMutation(
            event,
            group,
            "expense_correction",
            originalUuid,
            operation.id,
            operation.expectedRevisionId
        )
        if (!saved) {
            requireSameExpense(checkNotNull(getSavedCorrection(groupId, originalUuid, author, operation)), corrected)
        }
    }
}
