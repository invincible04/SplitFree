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

        val knownEventIds = eventRepo.getEventIds(groupId).toMutableSet()
        var imported = 0
        val group = groupRepo.getById(groupId)

        for (event in export.events) {
            val originalJson = event.originalEventJson ?: continue
            val parsed = NostrEvent.fromJson(originalJson) ?: continue
            if (!parsed.verify()) continue
            if (!eventValidator.isTimestampValidLenient(parsed.createdAt)) continue

            val eventId = parsed.id
            if (eventId in knownEventIds) continue

            val pubkey = parsed.pubkey
            val createdAt = parsed.createdAt
            val contentEncrypted = parsed.content
            val sig = parsed.sig
            val kind = parsed.kind

            var eventType = "unknown"
            var expenseUuid: String? = null
            for (tag in parsed.tags) {
                if (tag.size >= 2) {
                    when (tag[0]) {
                        "t" -> eventType = tag[1]
                        "x" -> expenseUuid = tag[1]
                    }
                }
            }

            if (group != null &&
                pubkey !in group.members &&
                eventType !in setOf("group_meta", "group_migrate", "key_revocation")
            ) {
                continue
            }

            if (eventType == "expense_correction" || eventType == "expense_delete") {
                val originalCreator = expenseUuid?.let { eventRepo.getExpenseByUuid(it, groupId)?.pubkey }
                if (!eventValidator.isCorrectionAuthorValid(eventType, pubkey, originalCreator)) continue
            }

            if (!eventValidator.isWithinRateLimit(pubkey)) continue

            val decrypted = try {
                encryption.decrypt(contentEncrypted, groupKey)
            } catch (_: Exception) {
                null
            }
            if (decrypted != null && !eventValidator.isContentSafe(decrypted)) continue

            eventRepo.insert(
                EventSnapshot(
                    eventId = eventId, groupId = groupId, pubkey = pubkey,
                    createdAt = createdAt, kind = kind,
                    contentEncrypted = contentEncrypted, eventType = eventType,
                    expenseUuid = expenseUuid, sig = sig,
                    receivedAt = System.currentTimeMillis() / 1000,
                    originalEventJson = originalJson
                )
            )
            knownEventIds += eventId
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
