package com.splitfree.ui.viewmodels.expense

import androidx.lifecycle.SavedStateHandle
import com.splitfree.domain.model.expense.SplitType
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persisted editor state.
 *
 * @property expenseId the logical expense UUID; for an edit it is the UUID of the expense being edited
 * @property commandId stable id of the save command this draft issues. A new expense is identified by its
 *   [expenseId]; an edit needs its own id because the expense UUID is shared with the original and every
 *   other edit of it. Survives process recreation so a retry is recognised as the same command.
 * @property expectedRevisionId for an edit, the event id of the revision the draft was seeded from; the
 *   correction applies only while the expense is still at that revision
 */
@Serializable
internal data class ExpenseDraft(
    val expenseId: String,
    val createdAt: Long,
    val localeTag: String,
    val commandId: String,
    val amount: String = "",
    val description: String = "",
    val currency: String = "INR",
    val paidBy: String = "",
    val authorPubkey: String = "",
    val category: String = "",
    val splitType: SplitType = SplitType.EQUAL,
    val inputs: Map<SplitType, Map<String, String>> = emptyMap(),
    val participants: Set<String> = emptySet(),
    val initialized: Boolean = false,
    val dirty: Boolean = false,
    val needsRecovery: Boolean = false,
    val expectedRevisionId: String? = null
)

internal class ExpenseDraftStore(private val handle: SavedStateHandle) {
    val restored: Boolean = handle.contains(KEY)

    private val json = Json { encodeDefaults = true }

    fun read(editingExpenseId: String? = null): ExpenseDraft {
        if (!restored) {
            val expenseId = UUID.randomUUID().toString()
            return ExpenseDraft(
                expenseId,
                System.currentTimeMillis() / 1000,
                Locale.getDefault().toLanguageTag(),
                commandId = if (editingExpenseId == null) expenseId else UUID.randomUUID().toString()
            )
        }
        val value = handle.get<Any?>(KEY)
        require(value is String) { "Invalid draft state" }
        return json.decodeFromString<ExpenseDraft>(value).also {
            val validExpenseId = if (it.initialized && editingExpenseId != null) {
                it.expenseId == editingExpenseId
            } else {
                UUID.fromString(it.expenseId).toString() == it.expenseId
            }
            require(validExpenseId && it.createdAt >= 0) {
                "Invalid draft identity"
            }
            require(UUID.fromString(it.commandId).toString() == it.commandId) { "Invalid draft command" }
            require(it.localeTag.isNotBlank()) { "Invalid draft locale" }
        }
    }

    fun write(draft: ExpenseDraft) {
        handle[KEY] = json.encodeToString(draft)
    }

    companion object {
        private const val KEY = "expenseDraft"
    }
}
