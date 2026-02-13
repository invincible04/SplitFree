package com.splitfree.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val groupId: String,
    val name: String,
    val description: String = "",
    val createdBy: String,
    val createdAt: Long,
    val members: String, // JSON array of pubkeys
    val relays: String, // JSON array of relay URLs
    val groupKey: String, // base64 encoded symmetric key
    val lastSyncTimestamp: Long = 0,
    val lastMetaTimestamp: Long = 0
)