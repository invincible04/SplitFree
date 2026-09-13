package com.splitfree.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "operation_journal")
data class ControlOperationEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val intentJson: String,
    val preparedJson: String?
)
