package com.splitfree.domain.usecase

import com.splitfree.data.local.EventDao
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.EventValidator
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject

@Serializable
data class SplitFreeExport(
    val version: Int = 1,
    val groupId: String,
    val exportedAt: Long,
    val events: List<ExportedEvent>,
    val hmac: String = ""  // HMAC-SHA256 over events JSON using group key
)

@Serializable
data class ExportedEvent(
    val eventId: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val contentEncrypted: String,
    val eventType: String,
    val expenseUuid: String? = null,
    val sig: String,
    val originalEventJson: String? = null
)

/**
 * Export group data as JSON for manual backup/transfer.
 */
class ExportGroupUseCase @Inject constructor(
    private val eventDao: EventDao,
    private val groupRepo: GroupRepository
) {
    private val json = Json { prettyPrint = true }

    suspend operator fun invoke(groupId: String): String {
        val groupKey = groupRepo.getGroupKey(groupId)
        val events = eventDao.getEventsByGroup(groupId)
        val exportedEvents = events.map { it.toExported() }
        val eventsJson = Json.encodeToString(exportedEvents)
        val hmac = if (groupKey != null) computeHmac(eventsJson, groupKey) else ""
        val export = SplitFreeExport(
            groupId = groupId,
            exportedAt = System.currentTimeMillis() / 1000,
            events = exportedEvents,
            hmac = hmac
        )
        return json.encodeToString(export)
    }

    private fun computeHmac(data: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(java.util.Base64.getDecoder().decode(key), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun EventEntity.toExported() = ExportedEvent(
        eventId = eventId, pubkey = pubkey, createdAt = createdAt,
        kind = kind, contentEncrypted = contentEncrypted, eventType = eventType,
        expenseUuid = expenseUuid, sig = sig, originalEventJson = originalEventJson
    )
}

/**
 * Import group data from a .splitfree JSON export.
 * Deduplicates against existing events.
 */
class ImportGroupUseCase @Inject constructor(
    private val eventDao: EventDao,
    private val groupRepo: GroupRepository,
    private val encryption: GroupEncryption
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @return number of new events imported
     */
    suspend operator fun invoke(jsonContent: String): Int {
        val export = json.decodeFromString<SplitFreeExport>(jsonContent)
        require(export.version == 1) { "Unsupported export version: ${export.version}" }

        val groupId = export.groupId
        val groupKey = groupRepo.getGroupKey(groupId)
            ?: throw IllegalStateException("No key for group $groupId — join the group first")

        require(export.hmac.isNotEmpty()) { "Export file missing integrity check (HMAC)" }
        val eventsJson = Json.encodeToString(export.events)
        val providedHmac = hexToBytes(export.hmac) ?: throw IllegalArgumentException("Invalid HMAC hex")
        val expectedHmac = computeHmacBytes(eventsJson, groupKey)
        require(MessageDigest.isEqual(providedHmac, expectedHmac)) {
            "Export file integrity check failed — file may have been tampered with"
        }

        val existingIds = eventDao.getEventIds(groupId).toSet()
        var imported = 0

        val group = groupRepo.getById(groupId)

        for (event in export.events) {
            if (event.eventId in existingIds) continue

            // Skip events from non-members
            if (group != null && event.pubkey !in group.members
                && event.eventType !in setOf("group_meta", "group_migrate", "key_revocation")) {
                continue
            }

            // Verify Nostr signature if original event JSON is available
            val originalJson = event.originalEventJson
            if (originalJson != null) {
                val parsed = NostrEvent.fromJson(originalJson)
                if (parsed == null || !parsed.verify()) continue
                if (!EventValidator.isTimestampValidLenient(parsed.createdAt)) continue
            }

            // Validate correction/deletion author
            if (event.eventType == "expense_correction" || event.eventType == "expense_delete") {
                val originalCreator = event.expenseUuid?.let { eventDao.getExpenseByUuid(it)?.pubkey }
                if (!EventValidator.isCorrectionAuthorValid(event.eventType, event.pubkey, originalCreator)) continue
            }

            // Rate limit per pubkey (defense-in-depth against crafted export files)
            if (!EventValidator.isWithinRateLimit(event.pubkey)) continue

            val decrypted = try { encryption.decrypt(event.contentEncrypted, groupKey) } catch (_: Exception) { null }
            eventDao.insert(
                EventEntity(
                    eventId = event.eventId,
                    groupId = groupId,
                    pubkey = event.pubkey,
                    createdAt = event.createdAt,
                    kind = event.kind,
                    contentEncrypted = event.contentEncrypted,
                    contentDecrypted = decrypted,
                    eventType = event.eventType,
                    expenseUuid = event.expenseUuid,
                    sig = event.sig,
                    receivedAt = System.currentTimeMillis() / 1000,
                    originalEventJson = event.originalEventJson
                )
            )
            imported++
        }
        return imported
    }

    private fun computeHmacBytes(data: String, key: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(java.util.Base64.getDecoder().decode(key), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return try {
            ByteArray(hex.length / 2) { i ->
                ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
            }
        } catch (_: Exception) { null }
    }
}
