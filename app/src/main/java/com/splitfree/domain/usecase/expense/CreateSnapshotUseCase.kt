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
 * Creates balance snapshots for incremental balance computation.
 * Triggered every 100 events or 30 days to avoid replaying the full event history.
 *
 * Nothing here runs inside a database transaction: balances are computed and the event is signed
 * outside any lock, and [EventPublisherContract.saveAndQueue] owns its own transaction. A concurrent
 * snapshot is detected by re-reading the latest snapshot right before saving.
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
     * Create a snapshot if ≥100 events or ≥30 days since the last one.
     *
     * @param groupId target group UUID
     * @return true if a snapshot was created, false if skipped
     */
    suspend operator fun invoke(groupId: String): Boolean {
        val group = groupRepo.getById(groupId) ?: return false
        // Only the creator may author snapshots (ComputeBalancesUseCase trusts no one else).
        // A group whose creator is unknown has no trusted snapshot author, so none are created.
        if (group.createdBy.isEmpty()) return false
        if (identity.getPublicKeyHex() != group.createdBy) return false
        val eventCount = eventRepo.getEventCount(groupId)
        // Snapshots are new ciphertext: encrypt with the loaded group's current epoch key only.
        val groupKey = groupRepo.getGroupKeyForEpoch(groupId, group.keyEpoch) ?: return false
        val lastSnapshot = eventRepo.getLatestEventByType(groupId, "snapshot")
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

        if (eventsSinceSnapshot < 100 && daysSinceSnapshot < 30) return false

        val balances = computeBalances(groupId)
        val eventIds = eventRepo.getEventIds(groupId)
        val eventHashes = eventIds.map { HashUtil.eventHashPrefix(it) }

        val snapshot =
            BalanceSnapshot(
                id = UUID.randomUUID().toString(),
                // Count the ids actually hashed so the snapshot is self-consistent even if events
                // arrived between the threshold check and this read.
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

        // Another sync path may have snapshotted while we were computing. Never stack two.
        val latestNow = eventRepo.getLatestEventByType(groupId, "snapshot")
        if (latestNow?.eventId != lastSnapshot?.eventId) {
            Log.w(TAG, "Skipping snapshot for group $groupId: a newer snapshot appeared during computation")
            return false
        }

        eventPublisher.saveAndQueue(event, groupId, encrypted, "snapshot", snapshot.id)
        return true
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
