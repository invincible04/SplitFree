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
     * Remove every stored key for [groupId] (the un-epoched entry under the plain group id and each epoch key)
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
     * Record the verified creator of a group whose creator is not yet recorded (imported from a
     * backup before its first creator-signed meta).
     *
     * Callers must only pass a `(createdBy, createdAt)` pair for which
     * [com.splitfree.domain.model.group.GroupIdentity.matches] holds for [groupId]. Unlike
     * [updateFromMeta] this does not touch the metadata watermark. Only fills an empty creator and
     * never restores a permanently revoked identity.
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
     * @param relays updated relay URL list; individually invalid entries are dropped, then the entire
     *   meta is rejected if the list fails [com.splitfree.domain.invite.InviteLinkCodec.fitsInviteLink]
     * @param eventTimestamp the meta event's `created_at`; must be positive
     * @param createdBy trusted creator pubkey update, or empty string to preserve existing value
     * @param memberNames full snapshot of member display names; absent or empty entries clear a name
     * @param description new description, or null to preserve the existing value
     * @param eventId id of the `group_meta` event being applied; breaks ties between metas that share
     *   an [eventTimestamp] so all devices converge on the same one
     * @param applyRoster false to preserve membership regardless of the source epoch
     * @param expectedKeyEpoch actual decryption epoch; a mismatch against the live row preserves its
     *   roster. Null is reserved for trusted local callers without an encrypted source event.
     * @param expectedCreator authenticated creator from the caller's snapshot; rejects the write if
     *   creator authority changed meanwhile. Null is reserved for trusted local callers.
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
        applyRoster: Boolean = true,
        expectedKeyEpoch: Int? = null,
        expectedCreator: String? = null
    ): Boolean

    /**
     * Install [epoch] as the group's current key epoch together with the roster the rotation defines,
     * atomically. Ordered by epoch only: a replay (group already at or past [epoch]) is a no-op and
     * the creator's metadata watermark is not touched.
     *
     * [expectedMembers] guards a locally prepared rotation against a concurrent membership change.
     * Receivers omit it because the creator's rotation defines the authoritative roster. An identity
     * this device has tombstoned is resolved through [resolveRoster] first: the creator may not have
     * seen the revocation yet, and refusing its rotation would strand this device at the old epoch.
     * @return true if the group advanced to [epoch] and the optional roster guard matched
     */
    suspend fun applyKeyRotation(
        groupId: String,
        epoch: Int,
        members: List<String>,
        memberNames: Map<String, String>,
        expectedMembers: List<String>? = null
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
     * Atomically replaces or removes an identity and records its permanent revocation tombstone.
     * Only the already-authenticated old identity may request this operation. Empty [newPubkey]
     * removes it without replacement. Replaying an already tombstoned identity is a successful no-op.
     *
     * When neither identity is in the roster the request is refused, unless [allowAbsent]: the
     * author-side projection of this device's own journaled revocation then records the tombstone
     * alone (no roster change, nobody re-added) so the revocation can complete against a roster that
     * dropped the user meanwhile. Remote revocations never pass it.
     */
    suspend fun applyIdentityRevocation(
        groupId: String,
        oldPubkey: String,
        newPubkey: String,
        eventTimestamp: Long,
        eventId: String,
        allowAbsent: Boolean = false
    ): Boolean

    /**
     * Applies this device's revocation tombstones to a roster another device authored: a tombstoned
     * identity becomes the replacement recorded with its revocation, or drops out when it has none.
     * Order and the remaining identities are preserved; duplicates collapse. The result is what
     * [applyKeyRotation] and [updateFromMeta] would install for [members].
     */
    suspend fun resolveRoster(groupId: String, members: List<String>): List<String>

    /**
     * Apply a member's own change (self-join / own display name) without touching the creator watermark.
     * Explicit display names compete with creator snapshots by (eventTimestamp, eventId); creator
     * wins an exact tie. Missing creator entries and empty names clear the name. A null self name
     * does not advance its name clock. Self-join ordering is independent of name ordering.
     *
     * [expectedKeyEpoch] is the actual decryption epoch. An absent author may only join at the live
     * epoch; existing members may still apply historical names. Null is for trusted local callers.
     * Returns true if a name or join clock was applied, false if stale, unauthorized, or a no-op.
     */
    suspend fun applyMemberSelfUpdate(
        groupId: String,
        author: String,
        eventTimestamp: Long,
        eventId: String,
        join: Boolean,
        displayName: String?,
        expectedKeyEpoch: Int? = null
    ): Boolean
}
