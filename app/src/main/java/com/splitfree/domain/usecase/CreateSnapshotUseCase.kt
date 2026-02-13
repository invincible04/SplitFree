package com.splitfree.domain.usecase

import androidx.room.withTransaction
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.local.EventDao
import com.splitfree.data.local.OutboxDao
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.data.local.entities.OutboxEntity
import com.splitfree.data.repository.GroupRepository
import com.splitfree.data.util.HashUtil
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.model.Balance
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

@Serializable
data class BalanceSnapshot(
    val id: String,
    val as_of_event_count: Int,
    val as_of_timestamp: Long,
    val balances: List<SnapshotBalance>,
    val event_hashes: List<String>,
)

@Serializable
data class SnapshotBalance(
    val pubkey: String,
    val net: Long,
    val currency: String = "INR",
)

/**
 * Creates balance snapshots per design doc Section 9.6.
 * Triggered every 100 events or 30 days.
 */
class CreateSnapshotUseCase
    @Inject
    constructor(
        private val db: AppDatabase,
        private val eventDao: EventDao,
        private val outboxDao: OutboxDao,
        private val groupRepo: GroupRepository,
        private val computeBalances: ComputeBalancesUseCase,
        private val encryption: GroupEncryption,
        private val signer: EventSigner,
    ) {
        private val json = Json { ignoreUnknownKeys = true }

        suspend operator fun invoke(groupId: String): Boolean {
            return db.withTransaction {
                val eventCount = eventDao.getEventCount(groupId)
                val lastSnapshot = eventDao.getLatestEventByType(groupId, "snapshot")
                val lastSnapshotCount =
                    lastSnapshot?.let {
                        runCatching {
                            val content = it.contentDecrypted ?: return@withTransaction false
                            json.decodeFromString<BalanceSnapshot>(content).as_of_event_count
                        }.getOrDefault(0)
                    } ?: 0

                val eventsSinceSnapshot = eventCount - lastSnapshotCount
                val daysSinceSnapshot =
                    lastSnapshot?.let {
                        (System.currentTimeMillis() / 1000 - it.createdAt) / 86400
                    } ?: Long.MAX_VALUE

                if (eventsSinceSnapshot < 100 && daysSinceSnapshot < 30) return@withTransaction false

                val balances = computeBalances(groupId)
                val eventIds = eventDao.getEventIds(groupId)
                val eventHashes = eventIds.map { HashUtil.sha256Hex(it) }

                val snapshot =
                    BalanceSnapshot(
                        id = UUID.randomUUID().toString(),
                        as_of_event_count = eventCount,
                        as_of_timestamp = System.currentTimeMillis() / 1000,
                        balances = balances.map { SnapshotBalance(it.pubkey, it.net, it.currency) },
                        event_hashes = eventHashes,
                    )

                val groupKey = groupRepo.getGroupKey(groupId) ?: return@withTransaction false
                val plaintext = json.encodeToString(BalanceSnapshot.serializer(), snapshot)
                val encrypted = encryption.encrypt(plaintext, groupKey)

                val event =
                    signer.createSignedEvent(
                        groupId = groupId,
                        eventType = "snapshot",
                        encryptedContent = encrypted,
                        expenseUuid = snapshot.id,
                    )

                eventDao.insert(
                    EventEntity(
                        eventId = event.id,
                        groupId = groupId,
                        pubkey = event.pubkey,
                        createdAt = event.createdAt,
                        kind = 30078,
                        contentEncrypted = encrypted,
                        contentDecrypted = plaintext,
                        eventType = "snapshot",
                        expenseUuid = snapshot.id,
                        sig = event.sig,
                        receivedAt = System.currentTimeMillis() / 1000,
                        originalEventJson = event.toJson(),
                    ),
                )
                outboxDao.insert(
                    OutboxEntity(
                        eventId = event.id,
                        eventJson = event.toJson(),
                        createdAt = event.createdAt,
                    ),
                )
                true
            }
        }
    }
