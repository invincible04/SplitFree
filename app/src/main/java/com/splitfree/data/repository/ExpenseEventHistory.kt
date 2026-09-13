package com.splitfree.data.repository

import com.splitfree.data.local.entities.EventEntity
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.expense.ExpenseIdentity
import com.splitfree.domain.repository.ExpenseRevisionConflictException

/**
 * Revision and command projections over an author's stored expense rows.
 *
 * Every lookup is author-qualified: the rows of one [ExpenseIdentity] are the rows that carry its UUID and
 * were signed by its author. Another member reusing the UUID owns a separate record that never appears
 * here. Ordering is canonical `(createdAt, eventId)`, matching the balance and expense-list projections.
 */
internal object ExpenseEventHistory {
    private val order = compareBy<EventEntity>({ it.createdAt }, { it.eventId })

    /**
     * The row whose payload is the current state of [identity]: its latest `expense_correction`, else its
     * earliest `expense`. Null when the author has deleted it or when no row of either type exists.
     */
    fun current(events: List<EventEntity>, identity: ExpenseIdentity): EventEntity? {
        val authored = events.filter { it.expenseUuid == identity.expenseUuid && it.pubkey == identity.authorPubkey }
        if (authored.any { it.eventType == "expense_delete" }) return null
        return authored.filter { it.eventType == "expense_correction" }.maxWithOrNull(order)
            ?: authored.filter { it.eventType == "expense" }.minWithOrNull(order)
    }

    /**
     * The current revision of [identity], which must be exactly [revisionId].
     *
     * @throws ExpenseRevisionConflictException when the expense is unknown, deleted, or has moved on
     */
    fun requireRevision(events: List<EventEntity>, identity: ExpenseIdentity, revisionId: String): EventEntity {
        val current = current(events, identity)
        if (current == null || current.eventId != revisionId) throw ExpenseRevisionConflictException()
        return current
    }

    /**
     * The row [author] published in [groupId] under the relay address of command [commandId] of [type], or
     * null. The address is read from the stored signed event, so a retried command is recognised without
     * a dedicated column.
     */
    fun command(
        events: List<EventEntity>,
        groupId: String,
        author: String,
        type: String,
        commandId: String
    ): EventEntity? {
        val address = listOf("d", EventSigner.relayAddress(groupId, type, commandId))
        return events.firstOrNull { entity ->
            entity.groupId == groupId &&
                entity.pubkey == author &&
                entity.eventType == type &&
                entity.originalEventJson?.let { NostrEvent.fromJson(it) }?.tags
                    ?.singleOrNull { it.firstOrNull() == "d" } == address
        }
    }
}
