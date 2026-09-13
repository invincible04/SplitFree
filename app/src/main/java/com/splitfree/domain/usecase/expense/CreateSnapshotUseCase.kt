package com.splitfree.domain.usecase.expense

import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.model.balance.BalanceSnapshot
import com.splitfree.domain.model.balance.SnapshotBalance
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.EventRepositoryContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.util.HashUtil
import com.splitfree.util.DebugLog as Log
import java.util.UUID
import javax.inject.Inject
import kotlinx.serialization.json.Json

/**
 * Creates creator-signed balance snapshots every 100 events or 30 days so members can skip a full replay.
 * Balances and the hashed coverage list come from one immutable ledger read, replayed in full, so a snapshot
 * never claims an event whose amount it lacks. Crypto runs outside any lock; the latest-snapshot, key-epoch
 * and creator re-checks share one transaction with [EventPublisherContract.saveAndQueue].
 */
class CreateSnapshotUseCase
@Inject
constructor(
    private val eventRepo: EventRepositoryContract,
    private val groupRepo: GroupRepositoryContract,
    private val computeBalances: ComputeBalancesUseCase,
    private val encryption: GroupEncryption,
    private val signer: EventSigner,
    private val eventPublisher: EventPublisherContract,
    private val identity: IdentityContract
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Create a snapshot if >= 100 events or >= 30 days since the last one.
     *
     * @param groupId target group UUID
     * @return true if a snapshot was created, false if skipped
     * @throws BalanceUnavailableException if the current epoch key is missing or any money event is unreadable
     */
    suspend operator fun invoke(groupId: String): Boolean {
        val group = groupRepo.getById(groupId) ?: return false
        // Only the creator may author snapshots (ComputeBalancesUseCase trusts no one else).
        // A group whose creator is unknown has no trusted snapshot author, so none are created.
        if (group.createdBy.isEmpty()) return false
        if (identity.getPublicKeyHex() != group.createdBy) return false
        // The one ledger read: threshold, balances and coverage are all derived from this list.
        val events = eventRepo.getEventsByGroup(groupId).toList()
        // Snapshots are new ciphertext: encrypt with the loaded group's current epoch key only.
        val groupKey = groupRepo.getGroupKeyForEpoch(groupId, group.keyEpoch)
            ?: throw BalanceUnavailableException("Missing key for epoch ${group.keyEpoch}")
        val lastSnapshot = events.latestSnapshot()
        val lastSnapshotCount =
            lastSnapshot?.let {
                runCatching {
                    // The previous snapshot may predate a rotation: open it with the key of its own epoch.
                    val snapshotKey = groupRepo.getGroupKeyForEpoch(groupId, it.keyEpoch) ?: return@runCatching 0
                    val content = encryption.decrypt(it.contentEncrypted, snapshotKey)
                    json.decodeFromString<BalanceSnapshot>(content).as_of_event_count
                }.getOrDefault(0)
            } ?: 0

        val eventsSinceSnapshot = events.size - lastSnapshotCount
        val daysSinceSnapshot =
            lastSnapshot?.let {
                (System.currentTimeMillis() / 1000 - it.createdAt) / 86400
            } ?: Long.MAX_VALUE

        if (eventsSinceSnapshot < 100 && daysSinceSnapshot < 30) return false

        // Full replay of exactly this list: the previous snapshot may cover remote events that are absent
        // locally, and its balances must not be re-published under a coverage list that omits them.
        val balances = computeBalances.computeWithExclusions(groupId, events, useSnapshots = false).balances
        val eventIds = events.map { it.eventId }
        val eventHashes = eventIds.map { HashUtil.eventHashPrefix(it) }

        val snapshot =
            BalanceSnapshot(
                id = UUID.randomUUID().toString(),
                as_of_event_count = eventIds.size,
                as_of_timestamp = System.currentTimeMillis() / 1000,
                balances = balances.map { SnapshotBalance(it.pubkey, it.net, it.currency) },
                event_hashes = eventHashes
            )

        val plaintext = json.encodeToString(BalanceSnapshot.serializer(), snapshot)
        val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8).size
        if (plaintextBytes > MAX_SNAPSHOT_PLAINTEXT) {
            Log.w(
                TAG,
                "Skipping snapshot for group $groupId: $plaintextBytes bytes for ${eventIds.size} events " +
                    "exceeds $MAX_SNAPSHOT_PLAINTEXT"
            )
            return false
        }
        val encrypted = encryption.encrypt(plaintext, groupKey)

        val event =
            signer.createSignedEvent(
                groupId = groupId,
                eventType = "snapshot",
                encryptedContent = encrypted,
                expenseUuid = snapshot.id
            )

        // Publication is guarded by the same transaction that re-reads the ledger head and the group: a
        // snapshot, rotation or creator change that landed during computation aborts this one.
        return eventRepo.withTransaction {
            val latestNow = eventRepo.getLatestEventByType(groupId, "snapshot")
            if (latestNow?.eventId != lastSnapshot?.eventId) {
                Log.w(TAG, "Skipping snapshot for group $groupId: a newer snapshot appeared during computation")
                return@withTransaction false
            }
            val currentGroup = groupRepo.getById(groupId) ?: return@withTransaction false
            if (currentGroup.keyEpoch != group.keyEpoch ||
                currentGroup.createdBy != group.createdBy ||
                identity.getPublicKeyHex() != group.createdBy
            ) {
                Log.w(TAG, "Skipping snapshot for group $groupId: key epoch or creator changed during computation")
                return@withTransaction false
            }
            eventPublisher.saveAndQueue(event, groupId, encrypted, "snapshot", snapshot.id)
            true
        }
    }

    companion object {
        private const val TAG = "CreateSnapshot"

        /**
         * Hard cap on the serialized snapshot, comfortably under the NIP-44 plaintext limit (65535 bytes)
         * and the 65536-char content check in `EventValidator.isContentSafe`.
         *
         * With 24-char event hashes each event costs about 27 bytes of JSON, so a group can accumulate
         * roughly 2,200 events before snapshots stop being produced and every member falls back to a full
         * replay. Acceptable for v1; a chunked or delta snapshot format is needed beyond that.
         */
        const val MAX_SNAPSHOT_PLAINTEXT = 60_000
    }
}
