package com.splitfree.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Event queued for relay publication.
 *
 * Inserted when an event is created locally, deleted after successful publish.
 * [retryCount] and [lastRetryAt] track failed publish attempts for backoff.
 */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey val eventId: String,
    val eventJson: String,
    val createdAt: Long,
    val retryCount: Int = 0,
    val lastRetryAt: Long? = null,
    val eventType: String? = null
)
