package com.splitfree.domain.usecase.export

import com.splitfree.data.local.dao.EventDao
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.domain.model.export.ExportedEvent
import com.splitfree.domain.model.export.SplitFreeExport
import com.splitfree.domain.repository.GroupRepositoryContract
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import kotlinx.serialization.json.Json

/**
 * Export group events as HMAC-signed JSON for manual backup or device transfer.
 *
 * @see ImportGroupUseCase for the corresponding import path
 */
class ExportGroupUseCase
@Inject
constructor(
    private val eventDao: EventDao,
    private val groupRepo: GroupRepositoryContract
) {
    private val json = Json { prettyPrint = true }

    /**
     * @param groupId target group UUID
     * @return pretty-printed JSON string of the export
     */
    suspend operator fun invoke(groupId: String): String {
        val groupKey = groupRepo.getGroupKey(groupId)
        val events = eventDao.getEventsByGroup(groupId)
        val exportedEvents = events.map { it.toExported() }
        val eventsJson = Json.encodeToString(exportedEvents)
        val hmac = if (groupKey != null) HmacUtil.compute(eventsJson, groupKey) else ""
        val export =
            SplitFreeExport(
                groupId = groupId,
                exportedAt = System.currentTimeMillis() / 1000,
                events = exportedEvents,
                hmac = hmac
            )
        return json.encodeToString(export)
    }

    private fun EventEntity.toExported() = ExportedEvent(
        eventId = eventId, pubkey = pubkey, createdAt = createdAt, kind = kind,
        contentEncrypted = contentEncrypted, eventType = eventType,
        expenseUuid = expenseUuid, sig = sig, originalEventJson = originalEventJson
    )
}

/**
 * Shared HMAC-SHA256 utility for export integrity checks.
 */
internal object HmacUtil {
    fun compute(data: String, groupKeyBase64: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(java.util.Base64.getDecoder().decode(groupKeyBase64), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun computeBytes(data: String, groupKeyBase64: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(java.util.Base64.getDecoder().decode(groupKeyBase64), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }
}
