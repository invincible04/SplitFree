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
     * @return 1 if applied; 0 if the row is missing, the clock is stale or a snapshot guard fails
     */
    @Query(
        "UPDATE `groups` SET name = :name, members = :members, relays = :relays, memberNames = :memberNames, " +
            "description = COALESCE(:description, description), " +
            "createdBy = CASE WHEN :createdBy != '' THEN :createdBy ELSE createdBy END, " +
            "lastMetaTimestamp = :eventTimestamp, lastMetaEventId = :eventId " +
            "WHERE groupId = :groupId " +
            "AND (:expectedKeyEpoch IS NULL OR keyEpoch = :expectedKeyEpoch) " +
            "AND (:expectedCreatedBy IS NULL OR createdBy = :expectedCreatedBy) " +
            "AND (:expectedMembers IS NULL OR members = :expectedMembers) " +
            "AND (:expectedMemberNames IS NULL OR memberNames = :expectedMemberNames) " +
            "AND (:expectedMemberClocks IS NULL OR memberClocks = :expectedMemberClocks) " +
            "AND (:expectedMetaTimestamp IS NULL OR lastMetaTimestamp = :expectedMetaTimestamp) " +
            "AND (:expectedMetaEventId IS NULL OR lastMetaEventId = :expectedMetaEventId) " +
            "AND (lastMetaTimestamp < :eventTimestamp " +
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
        eventId: String = "",
        expectedKeyEpoch: Int? = null,
        expectedMembers: String? = null,
        expectedMemberNames: String? = null,
        expectedMemberClocks: String? = null,
        expectedMetaTimestamp: Long? = null,
        expectedMetaEventId: String? = null,
        expectedCreatedBy: String? = null
    ): Int

    /**
     * Unconditional roster override used for key revocation (an identity swap has no epoch to order
     * it, so it rides the creator watermark). Raises `lastMetaTimestamp` to at least
     * [eventTimestamp] so a `group_meta` authored before the revocation cannot resurrect the revoked
     * key; metas authored after it still apply. `lastMetaEventId` follows the watermark: it is
     * replaced only when [eventTimestamp] wins (or ties). Name, relays and description are untouched.
     */
    @Query(
        "UPDATE `groups` SET members = :members, memberNames = :memberNames, " +
            "createdBy = CASE WHEN :createdBy != '' THEN :createdBy ELSE createdBy END, " +
            "lastMetaEventId = CASE WHEN :eventTimestamp >= lastMetaTimestamp " +
            "THEN :eventId ELSE lastMetaEventId END, " +
            "lastMetaTimestamp = MAX(lastMetaTimestamp, :eventTimestamp) " +
            "WHERE groupId = :groupId"
    )
    suspend fun overrideMembership(
        groupId: String,
        members: String,
        memberNames: String,
        createdBy: String,
        eventTimestamp: Long,
        eventId: String
    )

    /**
     * Apply a key rotation: the epoch advance and the roster it defines land in one statement, so a
     * crash can never leave the new epoch with the old roster (or vice versa). Guarded by
     * `keyEpoch < :epoch` so a replay is a no-op. Deliberately leaves the creator watermark alone:
     * rotations are ordered by epoch, creator metas by `(lastMetaTimestamp, lastMetaEventId)`.
     *
     * @return 1 if applied; 0 if the row is missing, the epoch is not newer or a snapshot guard fails
     */
    @Query(
        "UPDATE `groups` SET keyEpoch = :epoch, members = :members, memberNames = :memberNames " +
            "WHERE groupId = :groupId AND keyEpoch < :epoch " +
            "AND (:expectedMembers IS NULL OR members = :expectedMembers) " +
            "AND (:expectedKeyEpoch IS NULL OR keyEpoch = :expectedKeyEpoch) " +
            "AND (:expectedMemberClocks IS NULL OR memberClocks = :expectedMemberClocks) " +
            "AND (:expectedCreatedBy IS NULL OR createdBy = :expectedCreatedBy)"
    )
    suspend fun applyKeyRotation(
        groupId: String,
        epoch: Int,
        members: String,
        memberNames: String,
        expectedMembers: String? = null,
        expectedKeyEpoch: Int? = null,
        expectedMemberClocks: String? = null,
        expectedCreatedBy: String? = null
    ): Int

    /**
     * Apply a member's own change (self-join / own display name) computed by the repository.
     * Deliberately leaves `lastMetaTimestamp` / `lastMetaEventId` alone: a member editing their
     * own name must not block the creator's metadata watermark. Per-member name and join ordering
     * lives in `memberClocks`. Snapshot guards reject concurrent metadata, revocation and rotation
     * writes so the repository can re-read and merge without holding a mutex across Room calls.
     */
    @Query(
        "UPDATE `groups` SET members = :members, memberNames = :memberNames, memberClocks = :memberClocks " +
            "WHERE groupId = :groupId " +
            "AND (:expectedKeyEpoch IS NULL OR keyEpoch = :expectedKeyEpoch) " +
            "AND (:expectedCreatedBy IS NULL OR createdBy = :expectedCreatedBy) " +
            "AND (:expectedMembers IS NULL OR members = :expectedMembers) " +
            "AND (:expectedMemberNames IS NULL OR memberNames = :expectedMemberNames) " +
            "AND (:expectedMemberClocks IS NULL OR memberClocks = :expectedMemberClocks) " +
            "AND (:expectedMetaTimestamp IS NULL OR lastMetaTimestamp = :expectedMetaTimestamp) " +
            "AND (:expectedMetaEventId IS NULL OR lastMetaEventId = :expectedMetaEventId)"
    )
    suspend fun updateMemberSelf(
        groupId: String,
        members: String,
        memberNames: String,
        memberClocks: String,
        expectedKeyEpoch: Int? = null,
        expectedMembers: String? = null,
        expectedMemberNames: String? = null,
        expectedMemberClocks: String? = null,
        expectedMetaTimestamp: Long? = null,
        expectedMetaEventId: String? = null,
        expectedCreatedBy: String? = null
    ): Int

    @Query(
        "UPDATE `groups` SET members = :members, memberNames = :memberNames, memberClocks = :memberClocks, " +
            "createdBy = :createdBy, lastMetaTimestamp = :eventTimestamp, lastMetaEventId = :eventId " +
            "WHERE groupId = :groupId AND keyEpoch = :expectedKeyEpoch AND createdBy = :expectedCreatedBy " +
            "AND members = :expectedMembers AND memberNames = :expectedMemberNames " +
            "AND memberClocks = :expectedMemberClocks AND lastMetaTimestamp = :expectedMetaTimestamp " +
            "AND lastMetaEventId = :expectedMetaEventId"
    )
    suspend fun applyIdentityRevocation(
        groupId: String,
        members: String,
        memberNames: String,
        memberClocks: String,
        createdBy: String,
        eventTimestamp: Long,
        eventId: String,
        expectedKeyEpoch: Int,
        expectedMembers: String,
        expectedMemberNames: String,
        expectedMemberClocks: String,
        expectedMetaTimestamp: Long,
        expectedMetaEventId: String,
        expectedCreatedBy: String
    ): Int

    @Delete
    suspend fun delete(group: GroupEntity)

    @Query("UPDATE `groups` SET keyEpoch = :epoch WHERE groupId = :groupId")
    suspend fun updateKeyEpoch(groupId: String, epoch: Int)

    /**
     * Records the verified creator of a group whose `createdBy` is still empty (imported from a
     * backup before its first creator-signed meta).
     * Deliberately leaves `lastMetaTimestamp` alone so historical `group_meta` replays still apply.
     */
    @Query(
        "UPDATE `groups` SET createdBy = :createdBy, createdAt = :createdAt " +
            "WHERE groupId = :groupId AND createdBy = '' " +
            "AND (:expectedMemberClocks IS NULL OR memberClocks = :expectedMemberClocks)"
    )
    suspend fun updateCreator(
        groupId: String,
        createdBy: String,
        createdAt: Long,
        expectedMemberClocks: String? = null
    ): Int

    /**
     * Clears roster fields and the creator watermark for replay when an older control record arrives.
     * Keeps `memberClocks` so replay retains revocation tombstones, replacement links and rename clocks;
     * other columns, including canonical projection facts, are unchanged.
     *
     * @return 1 if reset; 0 if the row is missing or its watermark is not ahead of the supplied clock
     */
    @Query(
        "UPDATE `groups` SET members = '[]', memberNames = '{}', createdBy = '', " +
            "lastMetaTimestamp = 0, lastMetaEventId = '' " +
            "WHERE groupId = :groupId " +
            "AND (lastMetaTimestamp > :eventTimestamp " +
            "OR (lastMetaTimestamp = :eventTimestamp AND lastMetaEventId > :eventId))"
    )
    suspend fun resetRosterProjection(groupId: String, eventTimestamp: Long, eventId: String): Int

    /** Facts and their complete projection land together; no intermediate cleared roster is observable. */
    @Query(
        "UPDATE `groups` SET name = :name, description = :description, members = :members, " +
            "memberNames = :memberNames, memberClocks = :memberClocks, createdBy = :createdBy, " +
            "createdAt = :createdAt, relays = :relays, keyEpoch = :epoch, " +
            "lastMetaTimestamp = :timestamp, lastMetaEventId = :eventId, projectionJson = :projection " +
            "WHERE groupId = :groupId AND projectionJson = :expectedProjection " +
            "AND keyEpoch = :expectedEpoch AND members = :expectedMembers " +
            "AND memberClocks = :expectedClocks AND memberNames = :expectedNames " +
            "AND createdBy = :expectedCreator AND lastMetaTimestamp = :expectedTimestamp " +
            "AND lastMetaEventId = :expectedEventId"
    )
    suspend fun writeProjection(
        groupId: String,
        name: String,
        description: String,
        members: String,
        memberNames: String,
        memberClocks: String,
        createdBy: String,
        createdAt: Long,
        relays: String,
        epoch: Int,
        timestamp: Long,
        eventId: String,
        projection: String,
        expectedProjection: String,
        expectedEpoch: Int,
        expectedMembers: String,
        expectedClocks: String,
        expectedNames: String,
        expectedCreator: String,
        expectedTimestamp: Long,
        expectedEventId: String
    ): Int
}
