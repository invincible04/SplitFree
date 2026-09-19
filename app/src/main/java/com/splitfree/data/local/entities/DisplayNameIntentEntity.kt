package com.splitfree.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "display_name_intents")
data class DisplayNameIntentEntity(
    @PrimaryKey val identityPubkey: String,
    val revision: String,
    val name: String,
    val requestedAt: Long
)
