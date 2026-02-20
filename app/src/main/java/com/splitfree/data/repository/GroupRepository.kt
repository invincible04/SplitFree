package com.splitfree.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.splitfree.data.local.dao.GroupDao
import com.splitfree.data.local.entities.GroupEntity
import com.splitfree.data.util.EncryptedPrefsFactory
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.util.RelayDefaults
import com.splitfree.util.DebugLog as Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Manages group persistence and symmetric key storage.
 *
 * Group metadata (name, members, relays) is stored in Room. Symmetric group keys
 * are stored separately in EncryptedSharedPreferences and never written to the Room database.
 */
@Singleton
class GroupRepository
@Inject
constructor(
    private val groupDao: GroupDao,
    @ApplicationContext private val context: Context
) : com.splitfree.domain.repository.GroupRepositoryContract {
    private val json = Json
    private val stringListSerializer = ListSerializer(String.serializer())

    /** Encrypted storage for group keys — never stored in plaintext Room DB. */
    private val keyStore: SharedPreferences by lazy {
        EncryptedPrefsFactory.create(context, "splitfree_group_keys")
    }

    override fun observeAll(): Flow<List<Group>> = groupDao.observeAll().map { entities ->
        entities.map { it.toDomain() }
    }

    override suspend fun getAll(): List<Group> = groupDao.getAll().map { it.toDomain() }

    override suspend fun getById(groupId: String): Group? = groupDao.getById(groupId)?.toDomain()

    override fun observeById(groupId: String): Flow<Group?> = groupDao.observeById(groupId).map { it?.toDomain() }

    override suspend fun getGroupKey(groupId: String): String? = keyStore.getString(groupId, null)

    override suspend fun getMembers(groupId: String): List<String> = getById(groupId)?.members ?: emptyList()

    /** Remove a group key from encrypted storage (e.g., after migration). */
    override fun deleteGroupKey(groupId: String) {
        keyStore.edit().remove(groupId).apply()
    }

    suspend fun getGroupEntity(groupId: String): GroupEntity? = groupDao.getById(groupId)

    override suspend fun save(group: Group, groupKey: String) {
        // Store key in encrypted prefs, not in Room
        keyStore.edit().putString(group.id, groupKey).apply()
        groupDao.insert(
            GroupEntity(
                groupId = group.id,
                name = group.name,
                description = group.description,
                createdBy = group.createdBy,
                createdAt = group.createdAt,
                members = json.encodeToString(stringListSerializer, group.members),
                relays = json.encodeToString(stringListSerializer, group.relays)
            )
        )
    }

    override suspend fun updateLastSync(groupId: String, timestamp: Long) {
        groupDao.updateLastSync(groupId, timestamp)
    }

    override suspend fun updateFromMeta(
        groupId: String,
        name: String,
        members: List<String>,
        relays: List<String>,
        eventTimestamp: Long
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
        if (eventTimestamp > 0) {
            // Atomic update — only applies if eventTimestamp is newer than stored lastMetaTimestamp
            val updated = groupDao.updateMetaIfNewer(groupId, name, membersJson, relaysJson, eventTimestamp)
            if (updated > 0) {
                groupDao.updateLastMetaTimestamp(groupId, eventTimestamp)
            }
        } else {
            groupDao.updateMeta(groupId, name, membersJson, relaysJson)
        }
    }

    private fun GroupEntity.toDomain() = Group(
        id = groupId,
        name = name,
        description = description,
        createdBy = createdBy,
        createdAt = createdAt,
        members = try {
            json.decodeFromString(stringListSerializer, members)
        } catch (_: Exception) {
            emptyList()
        },
        relays = try {
            json.decodeFromString(stringListSerializer, relays)
        } catch (_: Exception) {
            emptyList()
        }
    )
}
