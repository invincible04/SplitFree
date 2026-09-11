package com.splitfree.domain.model.export

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Top-level structure of a `.splitfree` export file (format version 2).
 *
 * Self-contained backup: includes NIP-44 encrypted group keys, group metadata,
 * and all events. A fresh device with only the private key can restore everything.
 *
 * ## Authentication
 *
 * The file is a flat JSON object. Every field except [hmac] is authenticated: the MAC input is
 * [canonicalBody], the canonical JSON of this object with `hmac` blanked, and the key is derived
 * from the exporter's **private key** (see [com.splitfree.domain.crypto.ExportKeyDerivation]).
 * A group key would not do: every member holds it, so any member could forge a backup that the
 * importer trusts. Only the identity that made a backup can restore it.
 *
 * The importer re-encodes the decoded object with [CANONICAL_JSON] and compares. Anything the
 * decoder ignores (unknown fields) is therefore not covered, and not used either.
 *
 * ## Canonical form
 *
 * kotlinx.serialization emits fields in **declaration order**, and [CANONICAL_JSON] sets
 * `encodeDefaults = true` so a value omitted from the file encodes identically to an explicit
 * default. Adding, removing or reordering fields (here or in [ExportedEvent]) changes the
 * canonical bytes of existing content: bump [CURRENT_VERSION] when doing so.
 *
 * @property version format version; importers reject anything but [CURRENT_VERSION]
 * @property hmac lowercase hex HMAC-SHA256 over [canonicalBody]
 * @property encryptedGroupKey current epoch group key, NIP-44 encrypted to the exporter's pubkey
 * @property encryptedEpochKeys all epoch keys (epoch→encrypted key) for multi-epoch decryption
 * @property groupName human-readable group name
 * @property relays relay URLs for this group
 * @property keyEpoch the group's key epoch at export time
 */
@Serializable
data class SplitFreeExport(
    val version: Int = CURRENT_VERSION,
    val groupId: String,
    val exportedAt: Long,
    val events: List<ExportedEvent>,
    val hmac: String = "",
    val encryptedGroupKey: String = "",
    val groupName: String = "",
    val relays: List<String> = emptyList(),
    val keyEpoch: Int = 0,
    val encryptedEpochKeys: Map<String, String> = emptyMap()
) {
    /** The authenticated payload: canonical JSON of every field except [hmac]. */
    fun canonicalBody(): String = CANONICAL_JSON.encodeToString(serializer(), copy(hmac = ""))

    companion object {
        /** Format version written by [com.splitfree.domain.usecase.export.ExportGroupUseCase]. */
        const val CURRENT_VERSION = 2

        /**
         * Encoder used for the MAC input on both sides. Must never be changed without bumping
         * [CURRENT_VERSION]; see the class KDoc.
         */
        val CANONICAL_JSON = Json { encodeDefaults = true }
    }
}
