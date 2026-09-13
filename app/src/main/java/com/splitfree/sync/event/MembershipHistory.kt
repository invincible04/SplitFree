package com.splitfree.sync.event

import com.splitfree.data.local.dao.EventDao
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.domain.crypto.nip.Nip44
import com.splitfree.domain.model.group.KeyRotation
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.util.hexToBytes
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/**
 * Reconstructs who has ever been a member of a group from the locally stored, applied `key_rotation`
 * rows, so a reconciliation can admit history written by someone who has since been removed.
 *
 * Every rotation names the member it removed and the member list that remained, and each device
 * holds the rotation envelopes addressed to it (NIP-44 from the creator; the creator addresses one
 * to itself too). Rows this device cannot open are ignored rather than trusted.
 *
 * Scope (NS-13, bounded): this admits *pre-removal* history for reconciliation only. A record is
 * considered pre-removal when it decrypts under a key epoch strictly older than the rotation that
 * removed its author, which is the strongest check available from local state: a removed member
 * still holding an old epoch key can backdate a record under that key and it would pass. Closing
 * that gap needs a creator-signed membership checkpoint (a follow-up design item); until then the
 * [IngestionContext.LIVE] path is unchanged and never consults this class.
 */
@Singleton
class MembershipHistory
@Inject
constructor(
    private val eventDao: EventDao,
    private val groupRepo: GroupRepositoryContract,
    private val identity: IdentityContract
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Current members of [groupId] plus every pubkey that appears as `removedMember` or in `members`
     * of an applied `key_rotation` row this device can decrypt.
     */
    suspend fun historicalAuthors(groupId: String): Set<String> {
        val current = groupRepo.getById(groupId)?.members?.toSet() ?: emptySet()
        val authors = current.toMutableSet()
        for (rotation in decryptableRotations(groupId)) {
            if (rotation.removedMember.isNotEmpty()) authors += rotation.removedMember
            authors += rotation.members
        }
        return authors
    }

    /**
     * The epoch of the rotation that removed [pubkey] from [groupId], or null if no applied rotation
     * this device can read removed them. If they were removed more than once (removed, re-invited,
     * removed again), the latest removal wins: it is the only one that bounds their most recent
     * legitimate history.
     */
    suspend fun removalEpochOf(groupId: String, pubkey: String): Int? = decryptableRotations(groupId)
        .filter { it.removedMember == pubkey }
        .maxOfOrNull { it.epoch }

    /**
     * Applied `key_rotation` rows of [groupId] that decrypt with the conversation key between this
     * device and the row's author, parsed. Undecryptable or malformed rows are skipped.
     */
    private suspend fun decryptableRotations(groupId: String): List<KeyRotation> {
        val rows = eventDao.getEventsByType(groupId, EVENT_TYPE_KEY_ROTATION)
            .filter { it.applyState == EventEntity.APPLY_STATE_APPLIED }
        if (rows.isEmpty()) return emptyList()

        val privKey = identity.getPrivateKeyBytes()
        val convKeys = HashMap<String, ByteArray?>()
        try {
            return rows.mapNotNull { row ->
                val convKey = convKeys.getOrPut(row.pubkey) { conversationKeyOrNull(privKey, row.pubkey) }
                    ?: return@mapNotNull null
                try {
                    json.decodeFromString<KeyRotation>(Nip44.decrypt(row.contentEncrypted, convKey))
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
