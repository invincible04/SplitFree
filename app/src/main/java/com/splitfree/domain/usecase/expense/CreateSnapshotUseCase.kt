package com.splitfree.domain.usecase.expense

import androidx.room.withTransaction
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.local.dao.EventDao
import com.splitfree.data.util.HashUtil
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.model.balance.BalanceSnapshot
import com.splitfree.domain.model.balance.SnapshotBalance
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.sync.event.EventPublisher
import java.util.UUID
import javax.inject.Inject
import kotlinx.serialization.json.Json

/**
 * Creates balance snapshots for incremental balance computation.
 * Triggered every 100 events or 30 days to avoid replaying the full event history.
 */
class CreateSnapshotUseCase
@Inject
constructor(
    private val db: AppDatabase,
    private val eventDao: EventDao,
    private val groupRepo: GroupRepositoryContract,
    private val computeBalances: ComputeBalancesUseCase,
    private val encryption: GroupEncryption,
    private val signer: EventSigner,
    private val eventPublisher: EventPublisher
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Create a snapshot if ≥100 events or ≥30 days since the last one.
     *
     * @param groupId target group UUID
     * @return true if a snapshot was created, false if skipped
     */
    suspend operator fun invoke(groupId: String): Boolean {
        return db.withTransaction {
            val eventCount = eventDao.getEventCount(groupId)
            val groupKey = groupRepo.getGroupKey(groupId) ?: return@withTransaction false
            val lastSnapshot = eventDao.getLatestEventByType(groupId, "snapshot")
            val lastSnapshotCount =
                lastSnapshot?.let {
                    runCatching {
                        val content = encryption.decrypt(it.contentEncrypted, groupKey)
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
                    event_hashes = eventHashes
                )

            val plaintext = json.encodeToString(BalanceSnapshot.serializer(), snapshot)
            val encrypted = encryption.encrypt(plaintext, groupKey)

            val event =
                signer.createSignedEvent(
                    groupId = groupId,
                    eventType = "snapshot",
                    encryptedContent = encrypted,
                    expenseUuid = snapshot.id
                )

            eventPublisher.saveAndQueue(event, groupId, encrypted, "snapshot", snapshot.id)
            true
        }
    }
}
