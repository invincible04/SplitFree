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

    /** Newest group first; `groupId` breaks ties so the order is deterministic across reads. */
    @Query("SELECT * FROM `groups` ORDER BY createdAt DESC, groupId")
    fun observeAll(): Flow<List<GroupEntity>>

    /** Newest group first; `groupId` breaks ties so the order is deterministic across reads. */
    @Query("SELECT * FROM `groups` ORDER BY createdAt DESC, groupId")
    suspend fun getAll(): List<GroupEntity>

    @Query("SELECT * FROM `groups` WHERE groupId = :groupId")
    suspend fun getById(groupId: String): GroupEntity?

    @Query("SELECT * FROM `groups` WHERE groupId = :groupId")
    fun observeById(groupId: String): Flow<GroupEntity?>

    @Query("UPDATE `groups` SET lastSyncTimestamp = :timestamp WHERE groupId = :groupId")
    suspend fun updateLastSync(groupId: String, timestamp: Long)

    /**
     * Last-writer-wins metadata update. The metadata columns, `lastMetaTimestamp` and
     * `lastMetaEventId` are written in a single statement so a concurrent writer can never
     * observe the new members with the old watermark (or vice versa).
     *
     * Two metas sharing a `createdAt` are ordered by [eventId] so every device converges on
     * the same one regardless of arrival order.
     *
     * @param description new description, or null to leave the stored one untouched (local
     *   mutations such as rotation / revocation / join do not carry one)
     * @param eventId id of the `group_meta` event being applied; `""` for callers without one
     * @return number of rows updated: 1 if applied, 0 if ([eventTimestamp], [eventId]) was not newer
     */
    @Query(
        "UPDATE `groups` SET name = :name, members = :members, relays = :relays, memberNames = :memberNames, " +
            "description = COALESCE(:description, description), " +
            "createdBy = CASE WHEN :createdBy != '' THEN :createdBy ELSE createdBy END, " +
            "lastMetaTimestamp = :eventTimestamp, lastMetaEventId = :eventId " +
            "WHERE groupId = :groupId AND (lastMetaTimestamp < :eventTimestamp " +
            "OR (lastMetaTimestamp = :eventTimestamp AND lastMetaEventId < :eventId))"
    )
    suspend fun updateMetaIfNewer(
        groupId: String,
        name: String,
        members: String,
        relays: String,
        createdBy: String,
        eventTimestamp: Long,
        memberNames: String = "{}",
        description: String? = null,
        eventId: String = ""
    ): Int

    /**
     * Unconditional metadata update used for local mutations (rotation, revocation, join).
     * Always advances `lastMetaTimestamp` to at least [eventTimestamp] so a stale
     * `group_meta` replayed from a relay cannot revert the local change. `lastMetaEventId`
     * follows the watermark: it is replaced only when [eventTimestamp] wins (or ties).
     *
     * @param description new description, or null to leave the stored one untouched
     * @param eventId id to record alongside the watermark; `""` for local mutations
     */
    @Query(
        "UPDATE `groups` SET name = :name, members = :members, relays = :relays, memberNames = :memberNames, " +
            "description = COALESCE(:description, description), " +
            "createdBy = CASE WHEN :createdBy != '' THEN :createdBy ELSE createdBy END, " +
            "lastMetaEventId = CASE WHEN :eventTimestamp >= lastMetaTimestamp " +
            "THEN :eventId ELSE lastMetaEventId END, " +
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
        memberNames: String = "{}",
        description: String? = null,
        eventId: String = ""
    )

    /**
     * Apply a member's own change (self-join / own display name) computed by the repository.
     * Deliberately leaves `lastMetaTimestamp` / `lastMetaEventId` alone: a member editing their
     * own name must not block or be blocked by the creator's metadata watermark. Per-member
     * ordering lives in `memberClocks`.
     */
    @Query(
        "UPDATE `groups` SET members = :members, memberNames = :memberNames, memberClocks = :memberClocks WHERE groupId = :groupId"
    )
    suspend fun updateMemberSelf(groupId: String, members: String, memberNames: String, memberClocks: String)

    @Delete
    suspend fun delete(group: GroupEntity)

    @Query("UPDATE `groups` SET keyEpoch = :epoch WHERE groupId = :groupId")
    suspend fun updateKeyEpoch(groupId: String, epoch: Int)

    /**
     * Records the verified creator of a group whose `createdBy` was unknown (legacy import).
     * Deliberately leaves `lastMetaTimestamp` alone so historical `group_meta` replays still apply.
     */
    @Query("UPDATE `groups` SET createdBy = :createdBy, createdAt = :createdAt WHERE groupId = :groupId")
    suspend fun updateCreator(groupId: String, createdBy: String, createdAt: Long)
}
