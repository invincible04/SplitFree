package com.splitfree.sync.event

import com.splitfree.data.local.dao.EventDao
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.nip.Nip44
import com.splitfree.domain.model.group.IdentityHistory
import com.splitfree.domain.model.group.IdentityHistoryPage
import com.splitfree.domain.model.group.KeyRotation
import com.splitfree.domain.repository.EventSnapshot
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.MembershipHistoryContract
import com.splitfree.domain.util.hexToBytes
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/**
 * Admitted rotation facts survive personal-key replacement. Applied legacy envelopes supplement
 * those facts while they remain decryptable; missing history still makes this evidence incomplete.
 *
 * - Reconciliation admits former authors only under an epoch older than their latest known removal.
 *   This bounds key access, not creation time: a former member can still encrypt backdated history.
 * - [formerMembers] also includes revoked identities. Both live and catch-up ingestion use it
 *   for settlement counterparties, never to authorize a former member as a ledger author.
 */
@Singleton
class MembershipHistory
@Inject
constructor(
    private val eventDao: EventDao,
    private val groupRepo: GroupRepositoryContract,
    private val identity: IdentityContract
) : MembershipHistoryContract {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Current members of [groupId] plus every pubkey that appears as `removedMember` or in `members`
     * of an admitted rotation fact or a decryptable, applied legacy `key_rotation` row.
     */
    suspend fun historicalAuthors(groupId: String): Set<String> {
        val current = groupRepo.getById(groupId)?.members?.toSet() ?: emptySet()
        val authors = current.toMutableSet()
        for (rotation in knownRotations(groupId)) {
            if (rotation.removedMember.isNotEmpty()) authors += rotation.removedMember
            authors += rotation.members
        }
        return authors
    }

    suspend fun identityEvidence(groupId: String, encryption: GroupEncryption): IdentityHistory.Evidence =
        IdentityHistory(groupRepo, encryption).evaluate(
            groupId,
            eventDao.getEventsByType(groupId, IdentityHistoryPage.TYPE)
                .filter { it.applyState == EventEntity.APPLY_STATE_APPLIED }.map { row ->
                    EventSnapshot(
                        row.eventId, row.groupId, row.pubkey, row.createdAt, row.kind, row.contentEncrypted,
                        row.eventType, row.expenseUuid, row.sig, row.receivedAt, row.originalEventJson, row.keyEpoch
                    )
                }
        )

    override suspend fun formerMembers(groupId: String): Set<String> {
        val current = groupRepo.getById(groupId)?.members?.toSet() ?: emptySet()
        val former = HashSet<String>()
        for (rotation in knownRotations(groupId)) {
            if (rotation.removedMember.isNotEmpty()) former += rotation.removedMember
            former += rotation.members
        }
        former += groupRepo.retiredIdentities(groupId).revoked
        former -= current
        return former
    }

    /**
     * The epoch of the rotation that removed [pubkey] from [groupId], or null if no known rotation
     * removed them. If they were removed more than once (removed, re-invited,
     * removed again), the latest removal wins: it is the only one that bounds their most recent
     * legitimate history.
     */
    suspend fun removalEpochOf(groupId: String, pubkey: String): Int? = knownRotations(groupId)
        .filter { it.removedMember == pubkey }
        .maxOfOrNull { it.epoch }

    override suspend fun retainRotationHistory(groupId: String) {
        val retained = groupRepo.authenticatedRotations(groupId)
        val group = groupRepo.getById(groupId) ?: return
        val legacy = eventDao.getEventsByType(groupId, EVENT_TYPE_KEY_ROTATION)
            .filter { it.applyState == EventEntity.APPLY_STATE_APPLIED && it.eventId !in retained }
        for ((row, rotation) in decryptableRotations(legacy)) {
            // APPLIED is the existing authority decision. If a signed envelope is retained, bind
            // the row to it again before keeping plaintext evidence beyond the old key's lifetime.
            if (row.originalEventJson != null) {
                val event = NostrEvent.fromJson(row.originalEventJson) ?: continue
                if (!event.verify() ||
                    event.id != row.eventId ||
                    event.pubkey != row.pubkey ||
                    event.createdAt != row.createdAt ||
                    event.content != row.contentEncrypted ||
                    event.tags.filter { it.firstOrNull() == "g" } != listOf(listOf("g", groupId)) ||
                    event.tags.filter { it.firstOrNull() == "t" } != listOf(listOf("t", EVENT_TYPE_KEY_ROTATION))
                ) {
                    continue
                }
            }
            if (rotation.epoch <= 0 ||
                rotation.epoch > group.keyEpoch ||
                rotation.members.isEmpty() ||
                rotation.members.distinct().size != rotation.members.size ||
                rotation.removedMember in rotation.members
            ) {
                continue
            }
            check(
                groupRepo.retainAuthenticatedRotationHistory(
                    groupId,
                    rotation,
                    row.pubkey,
                    row.createdAt,
                    row.eventId
                )
            ) { "Could not retain applied rotation history before identity replacement" }
        }
    }

    /** Never need the old personal key again for a rotation already admitted to the projection. */
    private suspend fun knownRotations(groupId: String): List<KeyRotation> {
        val retained = groupRepo.authenticatedRotations(groupId)
        val legacy = eventDao.getEventsByType(groupId, EVENT_TYPE_KEY_ROTATION)
            .filter { it.applyState == EventEntity.APPLY_STATE_APPLIED && it.eventId !in retained }
        return (retained.values + decryptableRotations(legacy).map { it.second }).distinct()
    }

    /** Applied legacy rows only; malformed or no-longer-decryptable envelopes contribute no evidence. */
    private fun decryptableRotations(rows: List<EventEntity>): List<Pair<EventEntity, KeyRotation>> {
        if (rows.isEmpty()) return emptyList()

        val privKey = identity.getPrivateKeyBytes()
        val convKeys = HashMap<String, ByteArray?>()
        try {
            return rows.mapNotNull { row ->
                val convKey = convKeys.getOrPut(row.pubkey) { conversationKeyOrNull(privKey, row.pubkey) }
                    ?: return@mapNotNull null
                try {
                    row to json.decodeFromString<KeyRotation>(Nip44.decrypt(row.contentEncrypted, convKey))
                } catch (_: Exception) {
                    null
                }
            }
        } finally {
            convKeys.values.forEach { it?.fill(0) }
            privKey.fill(0)
        }
    }

    private fun conversationKeyOrNull(privKey: ByteArray, authorHex: String): ByteArray? = try {
        Nip44.getConversationKey(privKey, authorHex.hexToBytes())
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val EVENT_TYPE_KEY_ROTATION = "key_rotation"
    }
}
