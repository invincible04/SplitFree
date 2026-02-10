package com.splitfree.data.repository

import com.splitfree.data.local.EventDao
import com.splitfree.data.local.OutboxDao
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.data.local.entities.OutboxEntity
import com.splitfree.data.nostr.EventThrottler
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GiftWrapService
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.IdentityManager
import com.splitfree.domain.model.Expense
import com.splitfree.domain.model.Settlement
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExpenseRepository @Inject constructor(
    private val eventDao: EventDao,
    private val outboxDao: OutboxDao,
    private val groupRepo: GroupRepository,
    private val encryption: GroupEncryption,
    private val identity: IdentityManager,
    private val signer: EventSigner,
    private val throttler: EventThrottler,
    private val giftWrap: GiftWrapService
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun addExpense(expense: Expense, groupId: String) {
        val groupKey = groupRepo.getGroupKey(groupId)
            ?: error("Group $groupId not found")
        val plaintext = json.encodeToString(Expense.serializer(), expense)
        val encrypted = encryption.encrypt(plaintext, groupKey)

        val event = signer.createSignedEvent(
            groupId = groupId,
            eventType = "expense",
            encryptedContent = encrypted,
            expenseUuid = expense.id
        )
        saveEventAndQueue(event, groupId, encrypted, plaintext, "expense", expense.id)
    }

    suspend fun addSettlement(settlement: Settlement, groupId: String) {
        val groupKey = groupRepo.getGroupKey(groupId)
            ?: error("Group $groupId not found")
        val plaintext = json.encodeToString(Settlement.serializer(), settlement)
        val encrypted = encryption.encrypt(plaintext, groupKey)

        val event = signer.createSignedEvent(
            groupId = groupId,
            eventType = "settlement",
            encryptedContent = encrypted,
            expenseUuid = settlement.id
        )
        saveEventAndQueue(event, groupId, encrypted, plaintext, "settlement", settlement.id)
    }

    /**
     * Delete an expense. Only the original creator can delete.
     * @throws IllegalStateException if the expense doesn't exist or the caller isn't the creator.
     */
    suspend fun deleteExpense(expenseUuid: String, groupId: String, reason: String = "") {
        val original = eventDao.getExpenseByUuid(expenseUuid)
        checkNotNull(original) { "Expense $expenseUuid not found" }
        check(original.pubkey == identity.getPublicKey()) {
            "Only the creator can delete this expense"
        }

        val groupKey = groupRepo.getGroupKey(groupId)
            ?: error("Group $groupId not found")
        val plaintext = json.encodeToString(
            MapSerializer(String.serializer(), String.serializer()),
            mapOf("reason" to reason)
        )
        val encrypted = encryption.encrypt(plaintext, groupKey)

        val event = signer.createSignedEvent(
            groupId = groupId,
            eventType = "expense_delete",
            encryptedContent = encrypted,
            expenseUuid = expenseUuid
        )
        saveEventAndQueue(event, groupId, encrypted, plaintext, "expense_delete", expenseUuid)
    }

    /**
     * Correct an existing expense. Only the original creator can correct.
     * @throws IllegalStateException if the expense doesn't exist or the caller isn't the creator.
     */
    suspend fun correctExpense(originalUuid: String, corrected: Expense, groupId: String) {
        val original = eventDao.getExpenseByUuid(originalUuid)
        checkNotNull(original) { "Expense $originalUuid not found" }
        check(original.pubkey == identity.getPublicKey()) {
            "Only the creator can correct this expense"
        }

        val groupKey = groupRepo.getGroupKey(groupId)
            ?: error("Group $groupId not found")
        val plaintext = json.encodeToString(Expense.serializer(), corrected)
        val encrypted = encryption.encrypt(plaintext, groupKey)

        val event = signer.createSignedEvent(
            groupId = groupId,
            eventType = "expense_correction",
            encryptedContent = encrypted,
            expenseUuid = originalUuid
        )
        saveEventAndQueue(event, groupId, encrypted, plaintext, "expense_correction", originalUuid)
    }

    private suspend fun saveEventAndQueue(
        event: rust.nostr.sdk.Event,
        groupId: String,
        encrypted: String,
        plaintext: String,
        eventType: String,
        expenseUuid: String?
    ) {
        val eventJson = event.asJson()
        eventDao.insert(
            EventEntity(
                eventId = event.id().toHex(),
                groupId = groupId,
                pubkey = event.author().toHex(),
                createdAt = event.createdAt().asSecs().toLong(),
                kind = 30078,
                contentEncrypted = encrypted,
                contentDecrypted = plaintext,
                eventType = eventType,
                expenseUuid = expenseUuid,
                sig = event.signature().toHex(),
                receivedAt = System.currentTimeMillis() / 1000,
                originalEventJson = eventJson
            )
        )
        // Gift wrap for relay publishing (local store keeps unwrapped)
        val publishEvent = giftWrap.wrapIfEnabled(event)
        val publishJson = publishEvent.asJson()
        outboxDao.insert(
            OutboxEntity(
                eventId = publishEvent.id().toHex(),
                eventJson = publishJson,
                createdAt = publishEvent.createdAt().asSecs().toLong()
            )
        )
        throttler.enqueue(publishEvent)
    }
}
