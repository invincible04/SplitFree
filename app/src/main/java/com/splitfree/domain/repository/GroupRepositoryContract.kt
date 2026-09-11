package com.splitfree.domain.repository

import com.splitfree.domain.model.group.Group
import kotlinx.coroutines.flow.Flow

/**
 * Domain contract for group persistence and key management.
 */
interface GroupRepositoryContract {
    /** @return reactive stream of all groups */
    fun observeAll(): Flow<List<Group>>

    suspend fun getAll(): List<Group>

    /** @return group or null if not found locally */
    suspend fun getById(groupId: String): Group?

    fun observeById(groupId: String): Flow<Group?>

    /** @return base64-encoded symmetric group key for the current epoch, or null */
    suspend fun getGroupKey(groupId: String): String?

    /** @return base64-encoded symmetric group key for a specific epoch, or null */
    suspend fun getGroupKeyForEpoch(groupId: String, epoch: Int): String?

    /** @return list of member pubkeys for the group */
    suspend fun getMembers(groupId: String): List<String>

    /** Remove a group key from encrypted storage (e.g. after migration). */
    fun deleteGroupKey(groupId: String)

    /**
     * Persist a new group and its symmetric key.
     *
     * @param group the group to save
     * @param groupKey base64-encoded symmetric key stored in Keystore-backed encrypted storage
     */
    suspend fun save(group: Group, groupKey: String)

    /** Save a group key for a specific epoch. */
    suspend fun saveGroupKeyForEpoch(groupId: String, epoch: Int, groupKey: String)

    /** Update the group's current key epoch. */
    suspend fun updateKeyEpoch(groupId: String, epoch: Int)

    /**
     * Record the verified creator of a group whose creator was previously unknown (legacy import).
     *
     * Callers must only pass a `(createdBy, createdAt)` pair for which
     * [com.splitfree.domain.model.group.GroupIdentity.matches] holds for [groupId]. Unlike
     * [updateFromMeta] this does not touch the metadata watermark.
     */
    suspend fun updateCreator(groupId: String, createdBy: String, createdAt: Long)

    suspend fun updateLastSync(groupId: String, timestamp: Long)

    /**
     * Apply a `group_meta` update. Only overwrites if [eventTimestamp] is newer
     * than the stored `lastMetaTimestamp`. When [eventTimestamp] is 0 (a local
     * mutation such as rotation, revocation or join) the update is unconditional
     * and the watermark is advanced to the current time so stale replayed metas
     * cannot revert it.
     *
     * @param groupId target group UUID
     * @param name updated group name
     * @param members updated member pubkey list
     * @param relays updated relay URL list
     * @param eventTimestamp the `createdAt` of the incoming group_meta event, or 0 for a local mutation
     * @param createdBy trusted creator pubkey update, or empty string to preserve existing value
     * @param memberNames optional map of member pubkey -> display name
     */
    suspend fun updateFromMeta(
        groupId: String,
        name: String,
        members: List<String>,
        relays: List<String>,
        eventTimestamp: Long = 0,
        createdBy: String = "",
        memberNames: Map<String, String> = emptyMap()
    )
}
