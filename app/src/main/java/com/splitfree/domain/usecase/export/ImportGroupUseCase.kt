package com.splitfree.domain.usecase.export

import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.export.SplitFreeExport
import com.splitfree.domain.repository.EventRepositoryContract
import com.splitfree.domain.repository.EventSnapshot
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.validation.EventValidator
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.serialization.json.Json

/**
 * Import group events from a `.splitfree` JSON export.
 * Verifies HMAC integrity, validates signatures, and deduplicates against existing events.
 */
class ImportGroupUseCase
@Inject
constructor(
    private val eventRepo: EventRepositoryContract,
    private val groupRepo: GroupRepositoryContract,
    private val encryption: GroupEncryption,
    private val eventValidator: EventValidator
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @param jsonContent raw JSON string from a `.splitfree` export file
     * @return number of new events imported (duplicates are skipped)
     * @throws IllegalArgumentException if HMAC is missing, invalid, or version is unsupported
     * @throws IllegalStateException if the group key is not available locally
     */
    suspend operator fun invoke(jsonContent: String): Int {
        val export = json.decodeFromString<SplitFreeExport>(jsonContent)
        require(export.version == 1) { "Unsupported export version: ${export.version}" }

        val groupId = export.groupId
        val groupKey =
            groupRepo.getGroupKey(groupId)
                ?: throw IllegalStateException("No key for group $groupId — join the group first")

        require(export.hmac.isNotEmpty()) { "Export file missing integrity check (HMAC)" }
        val eventsJson = Json.encodeToString(export.events)
        val providedHmac = hexToBytes(export.hmac) ?: throw IllegalArgumentException("Invalid HMAC hex")
        val expectedHmac = HmacUtil.computeBytes(eventsJson, groupKey)
        require(MessageDigest.isEqual(providedHmac, expectedHmac)) {
            "Export file integrity check failed — file may have been tampered with"
        }

        val existingIds = eventRepo.getEventIds(groupId).toSet()
        var imported = 0
        val group = groupRepo.getById(groupId)

        for (event in export.events) {
            if (event.eventId in existingIds) continue

            if (group != null &&
                event.pubkey !in group.members &&
                event.eventType !in setOf("group_meta", "group_migrate", "key_revocation")
            ) {
                continue
            }

            val originalJson = event.originalEventJson
            if (originalJson != null) {
                val parsed = NostrEvent.fromJson(originalJson)
                if (parsed == null || !parsed.verify()) continue
                if (!eventValidator.isTimestampValidLenient(parsed.createdAt)) continue
            }

            if (event.eventType == "expense_correction" || event.eventType == "expense_delete") {
                val originalCreator = event.expenseUuid?.let { eventRepo.getExpenseByUuid(it)?.pubkey }
                if (!eventValidator.isCorrectionAuthorValid(event.eventType, event.pubkey, originalCreator)) continue
            }

            if (!eventValidator.isWithinRateLimit(event.pubkey)) continue

            val decrypted = try {
                encryption.decrypt(event.contentEncrypted, groupKey)
            } catch (_: Exception) {
                null
            }
            if (decrypted != null && !eventValidator.isContentSafe(decrypted)) continue

            eventRepo.insert(
                EventSnapshot(
                    eventId = event.eventId, groupId = groupId, pubkey = event.pubkey,
                    createdAt = event.createdAt, kind = event.kind,
                    contentEncrypted = event.contentEncrypted, eventType = event.eventType,
                    expenseUuid = event.expenseUuid, sig = event.sig,
                    receivedAt = System.currentTimeMillis() / 1000,
                    originalEventJson = event.originalEventJson
                )
            )
            imported++
        }
        return imported
    }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return try {
            ByteArray(hex.length / 2) { i ->
                ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
            }
        } catch (_: Exception) {
            null
        }
    }
}
