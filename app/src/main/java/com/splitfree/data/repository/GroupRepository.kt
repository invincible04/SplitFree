package com.splitfree.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.splitfree.data.local.GroupDao
import com.splitfree.data.local.entities.GroupEntity
import com.splitfree.domain.model.Group
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GroupRepository @Inject constructor(
    private val groupDao: GroupDao,
    @ApplicationContext private val context: Context
) {
    private val json = Json
    private val stringListSerializer = ListSerializer(String.serializer())

    /** Encrypted storage for group keys — never stored in plaintext Room DB. */
    private val keyStore: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "splitfree_group_keys",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun observeAll(): Flow<List<Group>> = groupDao.observeAll().map { entities ->
        entities.map { it.toDomain() }
    }

    suspend fun getAll(): List<Group> = groupDao.getAll().map { it.toDomain() }

    suspend fun getById(groupId: String): Group? = groupDao.getById(groupId)?.toDomain()

    fun observeById(groupId: String): Flow<Group?> = groupDao.observeById(groupId).map { it?.toDomain() }

    suspend fun getGroupKey(groupId: String): String? {
        // Try encrypted store first, fall back to Room for migration
        val fromStore = keyStore.getString(groupId, null)
        if (fromStore != null) return fromStore
        // Migrate: read from Room, move to encrypted store, clear from Room
        val entity = groupDao.getById(groupId) ?: return null
        if (entity.groupKey.isNotBlank()) {
            keyStore.edit().putString(groupId, entity.groupKey).apply()
            groupDao.clearGroupKey(groupId)
            return entity.groupKey
        }
        return null
    }

    suspend fun getMembers(groupId: String): List<String> =
        getById(groupId)?.members ?: emptyList()

    /** Remove a group key from encrypted storage (e.g., after migration). */
    fun deleteGroupKey(groupId: String) {
        keyStore.edit().remove(groupId).apply()
    }

    suspend fun getGroupEntity(groupId: String): GroupEntity? {
        return groupDao.getById(groupId)
    }

    suspend fun save(group: Group, groupKey: String) {
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
                relays = json.encodeToString(stringListSerializer, group.relays),
                groupKey = "" // empty — real key is in encrypted store
            )
        )
    }

    suspend fun updateLastSync(groupId: String, timestamp: Long) {
        groupDao.updateLastSync(groupId, timestamp)
    }

    suspend fun updateFromMeta(groupId: String, name: String, members: List<String>, relays: List<String>) {
        groupDao.updateMeta(
            groupId,
            name,
            json.encodeToString(stringListSerializer, members),
            json.encodeToString(stringListSerializer, relays)
        )
    }

    private fun GroupEntity.toDomain() = Group(
        id = groupId,
        name = name,
        description = description,
        createdBy = createdBy,
        createdAt = createdAt,
        members = json.decodeFromString(stringListSerializer, members),
        relays = json.decodeFromString(stringListSerializer, relays)
    )
}
