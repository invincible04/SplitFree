package com.splitfree.domain.usecase

import com.splitfree.data.local.EventDao
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.GroupEncryption
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

@Serializable
data class SplitFreeExport(
    val version: Int = 1,
    val groupId: String,
    val exportedAt: Long,
    val events: List<ExportedEvent>
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
        val events = eventDao.getEventsByGroup(groupId)
        val export = SplitFreeExport(
            groupId = groupId,
            exportedAt = System.currentTimeMillis() / 1000,
            events = events.map { it.toExported() }
        )
        return json.encodeToString(export)
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

        val existingIds = eventDao.getEventIds(groupId).toSet()
        var imported = 0

        for (event in export.events) {
            if (event.eventId in existingIds) continue
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
}
