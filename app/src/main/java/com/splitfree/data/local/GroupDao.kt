package com.splitfree.data.local

import androidx.room.*
import com.splitfree.data.local.entities.GroupEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(group: GroupEntity)

    @Update
    suspend fun update(group: GroupEntity)

    @Query("SELECT * FROM `groups`")
    fun observeAll(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM `groups`")
    suspend fun getAll(): List<GroupEntity>

    @Query("SELECT * FROM `groups` WHERE groupId = :groupId")
    suspend fun getById(groupId: String): GroupEntity?

    @Query("SELECT * FROM `groups` WHERE groupId = :groupId")
    fun observeById(groupId: String): Flow<GroupEntity?>

    @Query("UPDATE `groups` SET lastSyncTimestamp = :timestamp WHERE groupId = :groupId")
    suspend fun updateLastSync(
        groupId: String,
        timestamp: Long,
    )

    @Query(
        "UPDATE `groups` SET name = :name, members = :members, relays = :relays WHERE groupId = :groupId AND lastMetaTimestamp < :eventTimestamp",
    )
    suspend fun updateMetaIfNewer(
        groupId: String,
        name: String,
        members: String,
        relays: String,
        eventTimestamp: Long,
    ): Int

    @Query("UPDATE `groups` SET name = :name, members = :members, relays = :relays WHERE groupId = :groupId")
    suspend fun updateMeta(
        groupId: String,
        name: String,
        members: String,
        relays: String,
    )

    @Query("UPDATE `groups` SET groupKey = '' WHERE groupId = :groupId")
    suspend fun clearGroupKey(groupId: String)

    @Query("UPDATE `groups` SET lastMetaTimestamp = :timestamp WHERE groupId = :groupId")
    suspend fun updateLastMetaTimestamp(
        groupId: String,
        timestamp: Long,
    )

    @Delete
    suspend fun delete(group: GroupEntity)
}
