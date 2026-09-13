package com.splitfree.domain.repository

import com.splitfree.domain.model.group.Group
import kotlinx.coroutines.flow.Flow

/**
 * Domain contract for group persistence and key management.
 */
interface GroupRepositoryContract {
    /** @return reactive stream of all groups */
    fun observeAll(): Flow<List<Group>>

    suspend fun getAll(): List<Group>

    /** @return group or null if not found locally */
    suspend fun getById(groupId: String): Group?

    fun observeById(groupId: String): Flow<Group?>

    /** @return base64-encoded symmetric group key for the current epoch, or null */
    suspend fun getGroupKey(groupId: String): String?

    /** @return base64-encoded symmetric group key for a specific epoch, or null */
    suspend fun getGroupKeyForEpoch(groupId: String, epoch: Int): String?

    /** @return list of member pubkeys for the group */
    suspend fun getMembers(groupId: String): List<String>

    /**
     * Remove every stored key for [groupId] (the un-epoched legacy entry and each epoch key)
     * from encrypted storage. Intended for a "leave group" flow; the Room row itself is not
     * touched, so callers must delete or hide the group separately.
     */
    suspend fun deleteGroupKey(groupId: String)

    /**
     * Persist a new group and its symmetric key.
     *
     * @param group the group to save
     * @param groupKey base64-encoded symmetric key stored in Keystore-backed encrypted storage
     */
    suspend fun save(group: Group, groupKey: String)

    /**
     * Save a group key for a specific epoch. Epoch key material is immutable: the same key again is a
     * no-op, a different key for an epoch that already has one throws [IllegalStateException].
     */
    suspend fun saveGroupKeyForEpoch(groupId: String, epoch: Int, groupKey: String)

    /** Update the group's current key epoch. */
    suspend fun updateKeyEpoch(groupId: String, epoch: Int)

    /**
     * Record the verified creator of a group whose creator was previously unknown (legacy import).
     *
     * Callers must only pass a `(createdBy, createdAt)` pair for which
     * [com.splitfree.domain.model.group.GroupIdentity.matches] holds for [groupId]. Unlike
     * [updateFromMeta] this does not touch the metadata watermark.
     */
    suspend fun updateCreator(groupId: String, createdBy: String, createdAt: Long)

    suspend fun updateLastSync(groupId: String, timestamp: Long)

    /**
     * Apply a creator's `group_meta`, last-writer-wins on `(eventTimestamp, eventId)` against the
     * stored `(lastMetaTimestamp, lastMetaEventId)`. A stale or replayed meta is a no-op.
     *
     * Two orderings coexist and this method respects both:
     * - The roster is epoch-scoped. A meta encrypted under an older key epoch than the group's
     *   current one predates a rotation and may not touch membership ([applyRoster] = false): the
     *   stored roster is kept while name, relays, description and names are still updated.
     * - Each member's own display name is ordered by that member's clock
     *   ([applyMemberSelfUpdate]). A creator meta older than a member's own rename keeps the
     *   member's name; otherwise the creator's map wins for that member.
     *
     * @param groupId target group UUID
     * @param name updated group name
     * @param members the roster the meta carries (ignored when [applyRoster] is false)
     * @param relays updated relay URL list
     * @param eventTimestamp the meta event's `created_at`; must be positive
     * @param createdBy trusted creator pubkey update, or empty string to preserve existing value
     * @param memberNames map of member pubkey -> display name; an empty value clears a name
     * @param description new description, or null to preserve the existing value
     * @param eventId id of the `group_meta` event being applied; breaks ties between metas that share
     *   an [eventTimestamp] so all devices converge on the same one
     * @param applyRoster false when the meta decrypted under an epoch older than the group's current one
     * @return true if the meta was newer and applied
     */
    suspend fun updateFromMeta(
        groupId: String,
        name: String,
        members: List<String>,
        relays: List<String>,
        eventTimestamp: Long,
        createdBy: String = "",
        memberNames: Map<String, String> = emptyMap(),
        description: String? = null,
        eventId: String = "",
        applyRoster: Boolean = true
    ): Boolean

    /**
     * Install [epoch] as the group's current key epoch together with the roster the rotation defines,
     * atomically. Ordered by epoch only: a replay (group already at or past [epoch]) is a no-op and
     * the creator's metadata watermark is not touched.
     *
     * @return true if the group advanced to [epoch]
     */
    suspend fun applyKeyRotation(
        groupId: String,
        epoch: Int,
        members: List<String>,
        memberNames: Map<String, String>
    ): Boolean

    /**
     * Unconditional roster override for key revocation (old pubkey -> new pubkey). Raises the creator
     * watermark to at least `(eventTimestamp, eventId)` so metas authored before the revocation cannot
     * resurrect the revoked key, while metas authored after it still apply.
     */
    suspend fun overrideMembership(
        groupId: String,
        members: List<String>,
        memberNames: Map<String, String>,
        createdBy: String,
        eventTimestamp: Long,
        eventId: String
    )

    /**
     * Apply a member's own change (self-join / own display name) without touching the creator watermark.
     * Ordered per member by (eventTimestamp, eventId); returns true if applied, false if stale/no-op.
     * displayName == null leaves the name unchanged; "" clears it. join adds author to members.
     */
    suspend fun applyMemberSelfUpdate(
        groupId: String,
        author: String,
        eventTimestamp: Long,
        eventId: String,
        join: Boolean,
        displayName: String?
    ): Boolean
}
