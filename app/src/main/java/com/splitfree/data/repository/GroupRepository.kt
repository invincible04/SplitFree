package com.splitfree.data.repository

import com.splitfree.data.local.GroupDao
import com.splitfree.data.local.entities.GroupEntity
import com.splitfree.domain.model.Group
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GroupRepository @Inject constructor(
    private val groupDao: GroupDao
) {
    private val json = Json
    private val stringListSerializer = ListSerializer(String.serializer())

    fun observeAll(): Flow<List<Group>> = groupDao.observeAll().map { entities ->
        entities.map { it.toDomain() }
    }

    suspend fun getAll(): List<Group> = groupDao.getAll().map { it.toDomain() }

    suspend fun getById(groupId: String): Group? = groupDao.getById(groupId)?.toDomain()

    suspend fun getGroupKey(groupId: String): String? = groupDao.getById(groupId)?.groupKey

    suspend fun getGroupEntity(groupId: String): GroupEntity? = groupDao.getById(groupId)

    suspend fun save(group: Group, groupKey: String) {
        groupDao.insert(
            GroupEntity(
                groupId = group.id,
                name = group.name,
                description = group.description,
                createdBy = group.createdBy,
                createdAt = group.createdAt,
                members = json.encodeToString(stringListSerializer, group.members),
                relays = json.encodeToString(stringListSerializer, group.relays),
                groupKey = groupKey
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
