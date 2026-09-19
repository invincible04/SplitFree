package com.splitfree.data.repository

import androidx.room.withTransaction
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.local.dao.DisplayNameDao
import com.splitfree.data.local.entities.DisplayNameIntentEntity
import com.splitfree.data.local.entities.DisplayNamePublicationEntity
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.DisplayNameClockDeferredException
import com.splitfree.domain.repository.DisplayNameIntent
import com.splitfree.domain.repository.DisplayNameRepositoryContract
import com.splitfree.domain.repository.PreparedDisplayName
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

@Singleton
class DisplayNameRepository @Inject constructor(private val dao: DisplayNameDao, private val db: AppDatabase) :
    DisplayNameRepositoryContract {
    override suspend fun saveIntent(intent: DisplayNameIntent) = dao.saveIntent(
        DisplayNameIntentEntity(intent.identityPubkey, intent.revision, intent.name, intent.requestedAt)
    )

    override suspend fun getPrepared(intent: DisplayNameIntent, groupId: String): PreparedDisplayName? {
        val row = dao.getPublication(intent.identityPubkey, groupId) ?: return null
        if (row.revision != intent.revision || row.preparedEventJson == null) return null
        return PreparedDisplayName(
            intent,
            Json.decodeFromString<Group>(checkNotNull(row.groupSnapshotJson)),
            checkNotNull(NostrEvent.fromJson(row.preparedEventJson))
        )
    }

    override suspend fun committedEventId(intent: DisplayNameIntent, groupId: String): String? =
        dao.getPublication(intent.identityPubkey, groupId)?.takeIf { it.revision == intent.revision }?.committedEventId

    override suspend fun reserveTimestamp(intent: DisplayNameIntent, groupId: String, floor: Long, now: Long): Long =
        db.withTransaction {
            val old = dao.getPublication(intent.identityPubkey, groupId)
            // Reservations survive revisions and discarded preparations; never reuse a logical clock.
            val previous = maxOf(floor, old?.lastReservedTimestamp ?: 0)
            if (now <= 0 || now > Long.MAX_VALUE - MAX_FUTURE_SECONDS || previous >= now + MAX_FUTURE_SECONDS) {
                throw DisplayNameClockDeferredException()
            }
            val timestamp = maxOf(now, previous + 1)
            dao.savePublication(
                DisplayNamePublicationEntity(intent.identityPubkey, groupId, intent.revision, timestamp)
            )
            timestamp
        }

    override suspend fun savePrepared(prepared: PreparedDisplayName) = db.withTransaction {
        val intent = prepared.intent
        val row = checkNotNull(dao.getPublication(intent.identityPubkey, prepared.group.id))
        check(row.revision == intent.revision && row.lastReservedTimestamp == prepared.event.createdAt)
        check(row.preparedEventJson == null || row.preparedEventJson == prepared.event.toJson())
        dao.savePublication(
            row.copy(
                groupSnapshotJson = Json.encodeToString(Group.serializer(), prepared.group),
                preparedEventJson = prepared.event.toJson()
            )
        )
    }

    override suspend fun discardPrepared(intent: DisplayNameIntent, groupId: String) = db.withTransaction {
        val row = dao.getPublication(intent.identityPubkey, groupId)
        if (row != null && row.revision == intent.revision && row.committedEventId == null) {
            // Keep the reserved timestamp so a retry sorts after the abandoned event.
            dao.savePublication(row.copy(groupSnapshotJson = null, preparedEventJson = null))
        }
    }

    companion object {
        const val MAX_FUTURE_SECONDS = 3600L
    }
}
