package com.splitfree.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_revisions")
data class SyncRevisionEntity(@PrimaryKey val groupId: String, @ColumnInfo(defaultValue = "0") val revision: Long = 0)
