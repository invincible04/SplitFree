package com.splitfree.data.repository

import com.splitfree.data.local.dao.GroupDao
import com.splitfree.data.local.entities.GroupEntity
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.SecureStorage
import com.splitfree.domain.util.RelayDefaults
import com.splitfree.util.DebugLog as Log
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Manages group persistence and symmetric key storage.
 *
 * Group metadata (name, members, relays, member display names) is stored in Room. Symmetric group keys
 * are stored separately in Android Keystore-backed encrypted storage and never
 * written to the Room database.
 */
@Singleton
class GroupRepository
@Inject
constructor(
    private val groupDao: GroupDao,
    @Named("groupKeys") private val keyStore: SecureStorage
) : GroupRepositoryContract {
    private val json = Json
    private val stringListSerializer = ListSerializer(String.serializer())
    private val nameMapSerializer = MapSerializer(
        String.serializer(),
        String.serializer()
    )

    override fun observeAll(): Flow<List<Group>> = groupDao.observeAll().map { entities ->
        entities.map { it.toDomain() }
    }

    override suspend fun getAll(): List<Group> = groupDao.getAll().map { it.toDomain() }

    override suspend fun getById(groupId: String): Group? = groupDao.getById(groupId)?.toDomain()

    override fun observeById(groupId: String): Flow<Group?> = groupDao.observeById(groupId).map { it?.toDomain() }

    /**
     * Returns the key for the group's *current* epoch.
     *
     * The un-epoched legacy key (stored under the plain `groupId`) is only a valid
     * substitute for epoch 0. Falling back to it for a later epoch would silently
     * encrypt post-rotation traffic with a key the removed member still holds, so
     * callers get `null` instead and must treat the group as unusable until the
     * epoch key arrives.
     */
    override suspend fun getGroupKey(groupId: String): String? {
        val group = groupDao.getById(groupId)
        val epoch = group?.keyEpoch ?: 0
        return getGroupKeyForEpoch(groupId, epoch)
    }

    override suspend fun getGroupKeyForEpoch(groupId: String, epoch: Int): String? =
        keyStore.getString("$groupId:$epoch", null)
            ?: if (epoch == 0) keyStore.getString(groupId, null) else null

    override suspend fun getMembers(groupId: String): List<String> = getById(groupId)?.members ?: emptyList()

    /** Remove a group key from encrypted storage (e.g., after migration). */
    override fun deleteGroupKey(groupId: String) {
        keyStore.remove(groupId)
    }

    suspend fun getGroupEntity(groupId: String): GroupEntity? = groupDao.getById(groupId)

    override suspend fun save(group: Group, groupKey: String) {
        // Persist the key FIRST. SecureStorage.putString commits synchronously and throws
        // SecureStorageException on failure, so if the key cannot be stored we never reach
        // groupDao.insert and no Room row exists without a recoverable key behind it.
        keyStore.putString("${group.id}:${group.keyEpoch}", groupKey)
        // Also store under plain groupId for backward compat at epoch 0
        if (group.keyEpoch == 0) keyStore.putString(group.id, groupKey)
        val safeMemberNames = sanitizeMemberNames(group.memberNames, group.members)
        groupDao.insert(
            GroupEntity(
                groupId = group.id,
                name = group.name,
                description = group.description,
                createdBy = group.createdBy,
                createdAt = group.createdAt,
                members = json.encodeToString(stringListSerializer, group.members),
                relays = json.encodeToString(stringListSerializer, group.relays),
                memberNames = json.encodeToString(nameMapSerializer, safeMemberNames),
                keyEpoch = group.keyEpoch
            )
        )
    }

    /**
     * Store the key for [epoch]. Throws [com.splitfree.domain.repository.SecureStorageException]
     * if the write cannot be durably committed; callers must not advance the group's epoch
     * or publish rotation events until this returns.
     */
    override suspend fun saveGroupKeyForEpoch(groupId: String, epoch: Int, groupKey: String) {
        keyStore.putString("$groupId:$epoch", groupKey)
    }

    override suspend fun updateKeyEpoch(groupId: String, epoch: Int) {
        groupDao.updateKeyEpoch(groupId, epoch)
    }

    override suspend fun updateLastSync(groupId: String, timestamp: Long) {
        groupDao.updateLastSync(groupId, timestamp)
    }

    override suspend fun updateFromMeta(
        groupId: String,
        name: String,
        members: List<String>,
        relays: List<String>,
        eventTimestamp: Long,
        createdBy: String,
        memberNames: Map<String, String>
    ) {
        if (members.size > RelayDefaults.MAX_GROUP_MEMBERS) {
            Log.w(
                "GroupRepository",
                "Rejecting group_meta with ${members.size} members (max ${RelayDefaults.MAX_GROUP_MEMBERS})"
            )
            return
        }
        val safeRelays = relays.filter { it.startsWith("wss://") && it.length <= 256 }
        val membersJson = json.encodeToString(stringListSerializer, members)
        val relaysJson = json.encodeToString(stringListSerializer, safeRelays)
        val safeMemberNames = sanitizeMemberNames(memberNames, members)
        val namesJson = json.encodeToString(nameMapSerializer, safeMemberNames)
        if (eventTimestamp > 0) {
            val updated = groupDao.updateMetaIfNewer(
                groupId,
                name,
                membersJson,
                relaysJson,
                createdBy,
                eventTimestamp,
                namesJson
            )
            if (updated > 0) {
                groupDao.updateLastMetaTimestamp(groupId, eventTimestamp)
            }
        } else {
            groupDao.updateMeta(groupId, name, membersJson, relaysJson, createdBy, namesJson)
        }
    }

    private fun GroupEntity.toDomain(): Group {
        val decodedMembers = try {
            json.decodeFromString(stringListSerializer, members)
        } catch (_: Exception) {
            emptyList()
        }
        val decodedRelays = try {
            json.decodeFromString(stringListSerializer, relays)
        } catch (_: Exception) {
            emptyList()
        }
        val decodedNames = try {
            json.decodeFromString(nameMapSerializer, memberNames)
        } catch (_: Exception) {
            emptyMap()
        }
        return Group(
            id = groupId,
            name = name,
            description = description,
            createdBy = createdBy,
            createdAt = createdAt,
            members = decodedMembers,
            relays = decodedRelays,
            memberNames = sanitizeMemberNames(decodedNames, decodedMembers),
            keyEpoch = keyEpoch
        )
    }

    private fun sanitizeMemberNames(names: Map<String, String>, members: List<String>): Map<String, String> {
        if (names.isEmpty() || members.isEmpty()) return emptyMap()
        val memberSet = members.toSet()
        return names.entries
            .asSequence()
            .filter { it.key in memberSet }
            .map { it.key to it.value.trim().take(50) }
            .filter { it.second.isNotEmpty() }
            .toMap()
    }
}
