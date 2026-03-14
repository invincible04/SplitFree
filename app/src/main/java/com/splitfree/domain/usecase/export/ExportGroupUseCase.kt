package com.splitfree.domain.usecase.export

import com.splitfree.domain.crypto.nip.Nip44
import com.splitfree.domain.model.export.ExportedEvent
import com.splitfree.domain.model.export.SplitFreeExport
import com.splitfree.domain.repository.EventRepositoryContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import kotlinx.serialization.json.Json

/**
 * Export group events as HMAC-signed JSON for backup or device transfer.
 *
 * Exports are fully self-contained: the group keys are NIP-44 encrypted to the
 * exporter's own pubkey, so a new device with only the private key can restore everything.
 *
 * @see ImportGroupUseCase for the corresponding import path
 */
class ExportGroupUseCase
@Inject
constructor(
    private val eventRepo: EventRepositoryContract,
    private val groupRepo: GroupRepositoryContract,
    private val identity: IdentityContract
) {
    private val json = Json { prettyPrint = true }

    /**
     * @param groupId target group UUID
     * @return pretty-printed JSON string of the export
     */
    suspend operator fun invoke(groupId: String): String {
        val group = groupRepo.getById(groupId)
        val groupKey = groupRepo.getGroupKey(groupId)
        val events = eventRepo.getEventsByGroup(groupId)
        val exportedEvents = events.map { e ->
            ExportedEvent(
                eventId = e.eventId, pubkey = e.pubkey, createdAt = e.createdAt, kind = e.kind,
                contentEncrypted = e.contentEncrypted, eventType = e.eventType,
                expenseUuid = e.expenseUuid, sig = e.sig, originalEventJson = e.originalEventJson,
                keyEpoch = e.keyEpoch
            )
        }
        val eventsJson = Json.encodeToString(exportedEvents)
        val hmac = if (groupKey != null) HmacUtil.compute(eventsJson, groupKey) else ""

        // NIP-44 encrypt the group key to self (only this private key can decrypt it)
        val encryptedGroupKey: String
        val encryptedEpochKeys: Map<String, String>
        if (groupKey != null) {
            val privKey = identity.getPrivateKeyBytes()
            try {
                val convKey = Nip44.getConversationKey(privKey, identity.getPublicKeyBytes())
                encryptedGroupKey = Nip44.encrypt(groupKey, convKey)
                // Export all epoch keys so events from before key rotations can be decrypted
                val epochKeys = mutableMapOf<String, String>()
                val currentEpoch = group?.keyEpoch ?: 0
                for (epoch in 0..currentEpoch) {
                    val key = groupRepo.getGroupKeyForEpoch(groupId, epoch) ?: continue
                    epochKeys[epoch.toString()] = Nip44.encrypt(key, convKey)
                }
                encryptedEpochKeys = epochKeys
            } finally {
                privKey.fill(0)
            }
        } else {
            encryptedGroupKey = ""
            encryptedEpochKeys = emptyMap()
        }

        val export = SplitFreeExport(
            groupId = groupId,
            exportedAt = System.currentTimeMillis() / 1000,
            events = exportedEvents,
            hmac = hmac,
            encryptedGroupKey = encryptedGroupKey,
            groupName = group?.name ?: "",
            relays = group?.relays ?: emptyList(),
            keyEpoch = group?.keyEpoch ?: 0,
            encryptedEpochKeys = encryptedEpochKeys
        )
        return json.encodeToString(export)
    }
}

/**
 * Shared HMAC-SHA256 utility for export integrity checks.
 */
internal object HmacUtil {
    fun compute(data: String, groupKeyBase64: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(Base64.getDecoder().decode(groupKeyBase64), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun computeBytes(data: String, groupKeyBase64: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(Base64.getDecoder().decode(groupKeyBase64), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }
}
