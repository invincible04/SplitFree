package com.splitfree.domain.usecase.expense

import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.repository.EventRepositoryContract
import com.splitfree.domain.repository.GroupRepositoryContract
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * Observes and decrypts expenses for a group as a reactive [Flow].
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
     * Observe decrypted expenses for a group, sorted newest-first.
     *
     * @param groupId target group UUID
     * @return reactive [Flow] of expenses; emits empty list if group key is unavailable
     */
    fun observe(groupId: String): Flow<List<Expense>> = eventRepo.observeEventsByGroup(groupId).map { events ->
        val groupKey = groupRepo.getGroupKey(groupId) ?: return@map emptyList()
        events
            .filter { it.eventType == "expense" && it.expenseUuid != null }
            .mapNotNull { e ->
                val content =
                    try {
                        encryption.decrypt(e.contentEncrypted, groupKey)
                    } catch (_: Exception) {
                        null
                    }
                content?.let { runCatching { json.decodeFromString<Expense>(it) }.getOrNull() }
            }.sortedByDescending { it.timestamp }
    }
}
