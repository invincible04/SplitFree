package com.splitfree.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.splitfree.data.local.entities.DisplayNameIntentEntity
import com.splitfree.data.local.entities.DisplayNamePublicationEntity

@Dao
interface DisplayNameDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveIntent(intent: DisplayNameIntentEntity)

    @Query("SELECT * FROM display_name_intents WHERE identityPubkey = :identityPubkey")
    suspend fun getIntent(identityPubkey: String): DisplayNameIntentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePublication(publication: DisplayNamePublicationEntity)

    @Query("SELECT * FROM display_name_publications WHERE identityPubkey = :identityPubkey AND groupId = :groupId")
    suspend fun getPublication(identityPubkey: String, groupId: String): DisplayNamePublicationEntity?

    @Query(
        "UPDATE display_name_publications SET committedEventId = :eventId " +
            "WHERE identityPubkey = :identityPubkey AND groupId = :groupId AND revision = :revision " +
            "AND preparedEventJson = :preparedEventJson"
    )
    suspend fun complete(
        identityPubkey: String,
        groupId: String,
        revision: String,
        preparedEventJson: String,
        eventId: String
    ): Int
}
