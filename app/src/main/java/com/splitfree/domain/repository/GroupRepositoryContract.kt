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

    /** @return base64-encoded symmetric group key from encrypted storage, or null */
    suspend fun getGroupKey(groupId: String): String?

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

    suspend fun updateLastSync(groupId: String, timestamp: Long)

    /**
     * Apply a `group_meta` update. Only overwrites if [eventTimestamp] is newer
     * than the stored `lastMetaTimestamp` (or unconditionally when 0).
     *
     * @param groupId target group UUID
     * @param name updated group name
     * @param members updated member pubkey list
     * @param relays updated relay URL list
     * @param eventTimestamp the `createdAt` of the incoming group_meta event
     * @param createdBy trusted creator pubkey update, or empty string to preserve existing value
     */
    suspend fun updateFromMeta(
        groupId: String,
        name: String,
        members: List<String>,
        relays: List<String>,
        eventTimestamp: Long = 0,
        createdBy: String = ""
    )
}
