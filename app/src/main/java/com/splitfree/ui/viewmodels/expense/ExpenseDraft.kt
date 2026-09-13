package com.splitfree.ui.viewmodels.expense

import androidx.lifecycle.SavedStateHandle
import com.splitfree.domain.model.expense.SplitType
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class ExpenseDraft(
    val expenseId: String,
    val createdAt: Long,
    val localeTag: String,
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
    val needsRecovery: Boolean = false
)

internal class ExpenseDraftStore(private val handle: SavedStateHandle) {
    val restored: Boolean = handle.contains(KEY)

    private val json = Json { encodeDefaults = true }

    fun read(editingExpenseId: String? = null): ExpenseDraft {
        if (!restored) {
            return ExpenseDraft(
                UUID.randomUUID().toString(),
                System.currentTimeMillis() / 1000,
                Locale.getDefault().toLanguageTag()
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
