package com.splitfree.domain.model.export

import kotlinx.serialization.Serializable

/**
 * Top-level structure of a `.splitfree` export file.
 *
 * @property hmac HMAC-SHA256 of the serialized events array, keyed by the group key.
 *               Used to verify integrity on import.
 */
@Serializable
data class SplitFreeExport(
    val version: Int = 1,
    val groupId: String,
    val exportedAt: Long,
    val events: List<ExportedEvent>,
    val hmac: String = ""
)
