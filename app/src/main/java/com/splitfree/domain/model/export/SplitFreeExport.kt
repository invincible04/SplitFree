package com.splitfree.domain.model.export

import com.splitfree.domain.model.group.CreatorTransition
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Top-level structure of a `.splitfree` export file (format version 3).
 *
 * Contains this device's stored events, available epoch keys and group bootstrap metadata.
 * Restore requires the exporting identity; missing history, missing keys and the outbox are not recovered.
 *
 * ## Authentication
 *
 * HMAC-SHA256 covers [canonicalBody], which includes every declared field with [hmac] set to empty.
 * The MAC key is derived from the exporter's private key, not the shared group key
 * (see [com.splitfree.domain.crypto.ExportKeyDerivation]).
 *
 * Import recomputes the MAC from the decoded object. Ignored unknown fields are neither authenticated
 * nor used. The MAC does not encrypt metadata; event payloads and group keys retain their encryption.
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
 * @property encryptedEpochKeys available epoch keys, keyed by decimal epoch and encrypted to the exporter
 * @property groupName human-readable group name
 * @property relays relay URLs for this group
 * @property keyEpoch the group's key epoch at export time
 * @property rootCreator original creator verified against [groupId], or empty when unverified locally
 * @property rootCreatedAt original creation time, not the time of export
 * @property creatorTransitions portable signed authority evidence; never historical group keys
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
    val encryptedEpochKeys: Map<String, String> = emptyMap(),
    val rootCreator: String = "",
    val rootCreatedAt: Long = 0,
    val creatorTransitions: List<CreatorTransition> = emptyList()
) {
    /** MAC input: canonical JSON of all declared fields, with [hmac] set to empty. */
    fun canonicalBody(): String = CANONICAL_JSON.encodeToString(serializer(), copy(hmac = ""))

    companion object {
        /** Format version written by [com.splitfree.domain.usecase.export.ExportGroupUseCase]. */
        const val CURRENT_VERSION = 3

        /**
         * Encoder used for the MAC input on both sides. Must never be changed without bumping
         * [CURRENT_VERSION]; see the class KDoc.
         */
        val CANONICAL_JSON = Json { encodeDefaults = true }
    }
}
