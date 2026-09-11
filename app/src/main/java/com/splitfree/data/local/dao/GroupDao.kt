package com.splitfree.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.splitfree.data.local.entities.GroupEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data access for expense groups.
 *
 * Group metadata (name, members, relays) is stored here. Symmetric group keys
 * are stored separately in [KeystoreEncryptedStorage][com.splitfree.data.util.KeystoreEncryptedStorage]
 * via [GroupRepository][com.splitfree.data.repository.GroupRepository].
 */
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
    suspend fun updateLastSync(groupId: String, timestamp: Long)

    /**
     * Last-writer-wins metadata update. The metadata columns and `lastMetaTimestamp`
     * are written in a single statement so a concurrent writer can never observe the
     * new members with the old watermark (or vice versa).
     *
     * @return number of rows updated: 1 if applied, 0 if [eventTimestamp] was not newer
     */
    @Query(
        "UPDATE `groups` SET name = :name, members = :members, relays = :relays, memberNames = :memberNames, " +
            "createdBy = CASE WHEN :createdBy != '' THEN :createdBy ELSE createdBy END, " +
            "lastMetaTimestamp = :eventTimestamp " +
            "WHERE groupId = :groupId AND lastMetaTimestamp < :eventTimestamp"
    )
    suspend fun updateMetaIfNewer(
        groupId: String,
        name: String,
        members: String,
        relays: String,
        createdBy: String,
        eventTimestamp: Long,
        memberNames: String = "{}"
    ): Int

    /**
     * Unconditional metadata update used for local mutations (rotation, revocation, join).
     * Always advances `lastMetaTimestamp` to at least [eventTimestamp] so a stale
     * `group_meta` replayed from a relay cannot revert the local change.
     */
    @Query(
        "UPDATE `groups` SET name = :name, members = :members, relays = :relays, memberNames = :memberNames, " +
            "createdBy = CASE WHEN :createdBy != '' THEN :createdBy ELSE createdBy END, " +
            "lastMetaTimestamp = MAX(lastMetaTimestamp, :eventTimestamp) " +
            "WHERE groupId = :groupId"
    )
    suspend fun updateMeta(
        groupId: String,
        name: String,
        members: String,
        relays: String,
        createdBy: String,
        eventTimestamp: Long,
        memberNames: String = "{}"
    )

    @Delete
    suspend fun delete(group: GroupEntity)

    @Query("UPDATE `groups` SET keyEpoch = :epoch WHERE groupId = :groupId")
    suspend fun updateKeyEpoch(groupId: String, epoch: Int)
}
