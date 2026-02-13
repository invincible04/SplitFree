package com.splitfree.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey val eventId: String,
    val eventJson: String,
    val createdAt: Long,
    val retryCount: Int = 0,
    val lastRetryAt: Long? = null,
)
