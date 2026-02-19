package com.splitfree.data.repository

import androidx.room.withTransaction
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.local.dao.EventDao
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.domain.repository.EventRepositoryContract
import com.splitfree.domain.repository.EventSnapshot
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Implements [EventRepositoryContract] backed by Room [EventDao].
 */
@Singleton
class EventRepository
@Inject
constructor(private val db: AppDatabase, private val eventDao: EventDao) :
    EventRepositoryContract {

    override suspend fun getEventsByGroup(groupId: String): List<EventSnapshot> =
        eventDao.getEventsByGroup(groupId).map { it.toSnapshot() }

    override fun observeEventsByGroup(groupId: String): Flow<List<EventSnapshot>> =
        eventDao.observeEventsByGroup(groupId).map { entities -> entities.map { it.toSnapshot() } }

    override suspend fun getEventIds(groupId: String): List<String> = eventDao.getEventIds(groupId)

    override suspend fun getDeletedExpenseUuids(groupId: String): List<String> =
        eventDao.getDeletedExpenseUuids(groupId)

    override suspend fun getExpenseByUuid(uuid: String): EventSnapshot? = eventDao.getExpenseByUuid(uuid)?.toSnapshot()

    override suspend fun getLatestEventByType(groupId: String, eventType: String): EventSnapshot? =
        eventDao.getLatestEventByType(groupId, eventType)?.toSnapshot()

    override suspend fun getEventCount(groupId: String): Int = eventDao.getEventCount(groupId)

    override suspend fun insertIfNew(snapshot: EventSnapshot): Boolean = eventDao.insertIfNew(snapshot.toEntity())

    override suspend fun insert(snapshot: EventSnapshot) = eventDao.insert(snapshot.toEntity())

    override suspend fun <T> withTransaction(block: suspend () -> T): T = db.withTransaction { block() }

    private fun EventEntity.toSnapshot() = EventSnapshot(
        eventId = eventId, groupId = groupId, pubkey = pubkey,
        createdAt = createdAt, kind = kind, contentEncrypted = contentEncrypted,
        eventType = eventType, expenseUuid = expenseUuid, sig = sig,
        receivedAt = receivedAt, originalEventJson = originalEventJson
    )

    private fun EventSnapshot.toEntity() = EventEntity(
        eventId = eventId, groupId = groupId, pubkey = pubkey,
        createdAt = createdAt, kind = kind, contentEncrypted = contentEncrypted,
        eventType = eventType, expenseUuid = expenseUuid, sig = sig,
        receivedAt = receivedAt, originalEventJson = originalEventJson
    )
}
