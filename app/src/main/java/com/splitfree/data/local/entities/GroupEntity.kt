package com.splitfree.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted expense group.
 *
 * @property members JSON array of member pubkeys
 * @property relays JSON array of relay URLs
 * @property memberNames JSON map of pubkey → display name
 * @property lastSyncTimestamp unix timestamp of the last successful relay sync
 * @property lastMetaTimestamp `createdAt` of the most recent group_meta event applied
 */
@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val groupId: String,
    val name: String,
    val description: String = "",
    val createdBy: String,
    val createdAt: Long,
    val members: String,
    val relays: String,
    val memberNames: String = "{}",
    val lastSyncTimestamp: Long = 0,
    val lastMetaTimestamp: Long = 0
)
