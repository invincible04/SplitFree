package com.splitfree.domain.usecase.export

import com.splitfree.domain.crypto.ExportKeyDerivation
import com.splitfree.domain.crypto.nip.Nip44
import com.splitfree.domain.model.export.ExportedEvent
import com.splitfree.domain.model.export.SplitFreeExport
import com.splitfree.domain.model.group.CreatorTransition
import com.splitfree.domain.model.group.GroupIdentity
import com.splitfree.domain.repository.EventRepositoryContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.util.toHex
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import kotlinx.serialization.json.Json

/**
 * Export group events as an HMAC-authenticated JSON backup for device transfer.
 *
 * Available epoch keys are NIP-44 encrypted to the exporter's identity; the file MAC is derived
 * from the same private key (see [SplitFreeExport]). Restore therefore requires that identity.
 * Events or epoch keys absent from this device cannot be recovered from this backup alone.
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
    // encodeDefaults so the written file spells out every authenticated field.
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    /**
     * @param groupId target group UUID
     * @return pretty-printed JSON string of the export
     */
    suspend operator fun invoke(groupId: String): String {
        val group = groupRepo.getById(groupId)
        val rootedGroup = group?.takeIf { GroupIdentity.matches(groupId, it.originalCreator, it.createdAt) }
        require(group?.creatorTransitions.isNullOrEmpty() || rootedGroup != null) {
            "Creator transitions require a verified original creator"
        }
        if (rootedGroup != null) {
            require(
                CreatorTransition.validate(
                    groupId,
                    rootedGroup.originalCreator,
                    rootedGroup.createdAt,
                    rootedGroup.creatorTransitions
                )
            ) { "Invalid creator transition evidence" }
        }
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

        val privKey = identity.getPrivateKeyBytes()
        try {
            // NIP-44 encrypt the group key to self (only this private key can decrypt it)
            val encryptedGroupKey: String
            val encryptedEpochKeys: Map<String, String>
            if (groupKey != null) {
                val convKey = Nip44.getConversationKey(privKey, identity.getPublicKeyBytes())
                encryptedGroupKey = Nip44.encrypt(groupKey, convKey)
                // Include available historical keys; a device may never have held every epoch.
                val epochKeys = mutableMapOf<String, String>()
                val currentEpoch = group?.keyEpoch ?: 0
                for (epoch in 0..currentEpoch) {
                    val key = groupRepo.getGroupKeyForEpoch(groupId, epoch) ?: continue
                    epochKeys[epoch.toString()] = Nip44.encrypt(key, convKey)
                }
                encryptedEpochKeys = epochKeys
            } else {
                encryptedGroupKey = ""
                encryptedEpochKeys = emptyMap()
            }

            val unsigned = SplitFreeExport(
                version = SplitFreeExport.CURRENT_VERSION,
                groupId = groupId,
                exportedAt = System.currentTimeMillis() / 1000,
                events = exportedEvents,
                encryptedGroupKey = encryptedGroupKey,
                groupName = group?.name ?: "",
                relays = group?.relays ?: emptyList(),
                keyEpoch = group?.keyEpoch ?: 0,
                encryptedEpochKeys = encryptedEpochKeys,
                rootCreator = rootedGroup?.originalCreator.orEmpty(),
                rootCreatedAt = rootedGroup?.createdAt ?: 0,
                creatorTransitions = rootedGroup?.creatorTransitions.orEmpty()
            )
            val export = unsigned.copy(hmac = ExportMac.compute(unsigned, privKey).toHex())
            return json.encodeToString(export)
        } finally {
            privKey.fill(0)
        }
    }
}

/**
 * HMAC-SHA256 over [SplitFreeExport.canonicalBody] under the identity-derived export key.
 */
internal object ExportMac {
    /**
     * @param export the export to authenticate; its `hmac` field is ignored
     * @param privKey the user's 32-byte private key; not modified, caller zeroes it
     * @return raw 32-byte MAC
     */
    fun compute(export: SplitFreeExport, privKey: ByteArray): ByteArray {
        val macKey = ExportKeyDerivation.deriveExportMacKey(privKey)
        try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(macKey, "HmacSHA256"))
            return mac.doFinal(export.canonicalBody().toByteArray(Charsets.UTF_8))
        } finally {
            macKey.fill(0)
        }
    }
}
