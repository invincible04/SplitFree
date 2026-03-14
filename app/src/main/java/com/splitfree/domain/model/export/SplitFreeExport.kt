package com.splitfree.domain.model.export

import kotlinx.serialization.Serializable

/**
 * Top-level structure of a `.splitfree` export file.
 *
 * Self-contained backup: includes NIP-44 encrypted group keys, group metadata,
 * and all events. A fresh device with only the private key can restore everything.
 *
 * @property hmac HMAC-SHA256 of the serialized events array, keyed by the group key.
 * @property encryptedGroupKey current epoch group key, NIP-44 encrypted to the exporter's pubkey.
 * @property encryptedEpochKeys all epoch keys (epoch→encrypted key) for multi-epoch decryption.
 * @property groupName human-readable group name.
 * @property relays relay URLs for this group.
 * @property keyEpoch the group's key epoch at export time.
 */
@Serializable
data class SplitFreeExport(
    val version: Int = 1,
    val groupId: String,
    val exportedAt: Long,
    val events: List<ExportedEvent>,
    val hmac: String = "",
    val encryptedGroupKey: String = "",
    val groupName: String = "",
    val relays: List<String> = emptyList(),
    val keyEpoch: Int = 0,
    val encryptedEpochKeys: Map<String, String> = emptyMap()
)
