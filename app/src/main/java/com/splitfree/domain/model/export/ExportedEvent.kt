package com.splitfree.domain.model.export

import kotlinx.serialization.Serializable

/**
 * A single event within a [SplitFreeExport] file.
 */
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
