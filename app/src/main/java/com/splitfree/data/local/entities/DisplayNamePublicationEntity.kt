package com.splitfree.data.local.entities

import androidx.room.Entity

@Entity(tableName = "display_name_publications", primaryKeys = ["identityPubkey", "groupId"])
data class DisplayNamePublicationEntity(
    val identityPubkey: String,
    val groupId: String,
    val revision: String,
    val lastReservedTimestamp: Long,
    val groupSnapshotJson: String? = null,
    val preparedEventJson: String? = null,
    val committedEventId: String? = null
)
