package com.splitfree.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted expense group.
 *
 * @property members JSON array of member pubkeys
 * @property relays JSON array of relay URLs
 * @property memberNames JSON map of pubkey → display name
 * @property lastSyncTimestamp unix timestamp of the last successful relay sync
 * @property lastMetaTimestamp projection watermark advanced by creator metadata and identity revocations
 * @property lastMetaEventId event ID paired with the watermark to break same-second ties
 * @property memberClocks JSON map of name/join clocks, revocation tombstones and replacement/successor links
 * @property projectionJson canonical checkpoint and control facts; empty for legacy rows awaiting projection
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
    val lastMetaTimestamp: Long = 0,
    val keyEpoch: Int = 0,
    val lastMetaEventId: String = "",
    val memberClocks: String = "{}",
    @ColumnInfo(defaultValue = "''") val projectionJson: String = ""
)
