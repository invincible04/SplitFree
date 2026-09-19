package com.splitfree.domain.repository

import com.splitfree.domain.model.group.CreatorTransition
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupControlFact
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.model.group.KeyRotation
import com.splitfree.domain.model.group.RetiredIdentities
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
     * Removes the plain group-key entry and sweeps stored epochs through the known current epoch.
     * If the group row is missing, uses a bounded orphan sweep. Does not delete the Room row.
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
     * Fills an unknown creator without advancing the metadata watermark.
     * Callers must verify `(createdBy, createdAt)` against [groupId] with
     * [com.splitfree.domain.model.group.GroupIdentity.matches]. A retired creator resolves through the
     * local replacement chain; a chain with no live end leaves the creator unset.
     */
    suspend fun updateCreator(groupId: String, createdBy: String, createdAt: Long)

    suspend fun updateLastSync(groupId: String, timestamp: Long)

    /**
     * Applies creator metadata using epoch-scoped roster ordering and `(eventTimestamp, eventId)` clocks.
     * With canonical facts, older metadata is retained for replay rather than rejected solely by the current
     * watermark. Checkpoint-only state accepts metadata newer than its stored watermark.
     *
     * A roster from an older epoch cannot replace the current roster. Each member's newer self-rename
     * survives a creator snapshot; otherwise that snapshot's name wins, including clearing omitted names.
     *
     * @param relays invalid entries are dropped; the remaining list must fit the invite relay budget
     * @param eventTimestamp positive event `created_at`, in epoch seconds
     * @param createdBy trusted creator update, or empty to preserve the current creator
     * @param memberNames complete creator name snapshot; absent or empty entries clear names
     * @param description null preserves the current description
     * @param eventId event ID, used to break timestamp ties
     * @param applyRoster false prevents this metadata from changing membership
     * @param expectedKeyEpoch actual decryption epoch; null is reserved for trusted local calls
     * @param expectedCreator authenticated creator snapshot; a changed current creator rejects the call
     * @return true if projected group fields or clocks changed; false can still retain a canonical fact
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
     * Directly overwrites roster/name fields and a nonempty creator, without recording retirement facts.
     * Advances the metadata timestamp; replaces its event ID when the incoming timestamp is at least equal.
     * Use [applyIdentityRevocation] when a permanent retirement tombstone is required.
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
     * Records retirement and rebuilds the roster and creator from canonical control facts.
     * Callers authenticate the retiring identity before this boundary. Empty [newPubkey] retires without
     * replacement; a named successor can inherit the roster seat, but balances follow only proven links.
     *
     * Competing retirements select the earliest `(eventTimestamp, eventId)`, independent of arrival order.
     * Losing facts are retained. Exact replay succeeds; reusing an event ID with different content fails.
     * Independent successor seats are preserved, and creator authority follows the canonical replacement.
     *
     * @param allowAbsent bypasses the initial roster-presence check for already-authorized replay or
     *   local journal recovery; it does not authorize the caller or explicitly re-add an absent identity
     * @param successorProven successor authorization was verified for this group and identity pair
     * @return true when accepted, including an idempotent replay or a retained losing fact
     */
    suspend fun applyIdentityRevocation(
        groupId: String,
        oldPubkey: String,
        newPubkey: String,
        eventTimestamp: Long,
        eventId: String,
        allowAbsent: Boolean = false,
        successorProven: Boolean = false
    ): Boolean

    /**
     * Clears roster, names, current creator and metadata watermark for checkpoint-based history replay
     * when the stored watermark is later than `(eventTimestamp, eventId)`.
     * Retains clocks, retirement links, other group fields and any canonical projection data.
     *
     * @return true if the reset occurred and the caller must replay control history
     */
    suspend fun resetRosterProjection(groupId: String, eventTimestamp: Long, eventId: String): Boolean

    /**
     * Applies this device's revocation tombstones to a roster another device authored: a tombstoned
     * identity becomes the replacement recorded with its revocation, or drops out when it has none.
     * Order and the remaining identities are preserved; duplicates collapse. The result is what
     * [applyKeyRotation] and [updateFromMeta] would install for [members]. A replacement chain is
     * followed to its terminal identity; one that loops (or is implausibly long) is invalid and its
     * seat is dropped, never an intermediate identity.
     */
    suspend fun resolveRoster(groupId: String, members: List<String>): List<String>

    /**
     * Returns local retirement tombstones and pre-resolved proven successor chains for balance attribution.
     * A missing proof ends a chain at its last reached identity. Cyclic or over-limit chains yield no successor,
     * so the balance stays on the original key. Unknown groups return [RetiredIdentities.NONE].
     */
    suspend fun retiredIdentities(groupId: String): RetiredIdentities

    /**
     * Applies a self-join or the author's own name without advancing the creator watermark.
     * Name updates compete by `(eventTimestamp, eventId)`; creator snapshots win exact ties.
     * A null name leaves its clock unchanged. Join and name clocks are independent.
     *
     * An absent author may join only at [expectedKeyEpoch] when it matches the current epoch; existing
     * members can supply historical names. Null is reserved for trusted local calls. Retired authors are refused.
     *
     * @return true if projected fields or clocks changed; false can still retain a canonical fact
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

    /** Previews a merge with local facts; null for a missing group, invalid evidence or conflicting event meaning. */
    suspend fun previewCreatorBootstrap(
        groupId: String,
        originalCreator: String,
        createdAt: Long,
        transitions: List<CreatorTransition>
    ): Group? = null

    /** Atomically unions verified root/certificates with local facts and recomputes canonical creator authority. */
    suspend fun mergeCreatorBootstrap(
        groupId: String,
        originalCreator: String,
        createdAt: Long,
        transitions: List<CreatorTransition>
    ): Boolean = false

    /** Whether this group can use durable canonical reconstruction; does not imply complete history. */
    suspend fun hasCanonicalProjection(groupId: String): Boolean = false

    /**
     * Full envelope authentication and decryption precede this boundary; epoch is the verified decryption epoch.
     * [expectedGroup] guards locally signed creator updates or self-joins against concurrent group changes.
     * A guarded self-join succeeds only if its author remains an unretired member after projection.
     */
    suspend fun applyAuthenticatedMeta(
        groupId: String,
        meta: GroupMeta,
        author: String,
        timestamp: Long,
        eventId: String,
        epoch: Int,
        expectedGroup: Group? = null
    ): Boolean = false

    suspend fun applyAuthenticatedRevocation(
        groupId: String,
        oldPubkey: String,
        newPubkey: String,
        timestamp: Long,
        eventId: String,
        epoch: Int,
        successorProven: Boolean
    ): Boolean = applyIdentityRevocation(
        groupId,
        oldPubkey,
        newPubkey,
        timestamp,
        eventId,
        allowAbsent = true,
        successorProven = successorProven
    )

    /**
     * Admitted rotation payloads indexed by signed event ID, including projection-neutral legacy history.
     * These are durable local admission records, not signed envelopes or fresh signature verification.
     */
    suspend fun authenticatedRotations(groupId: String): Map<String, KeyRotation> = emptyMap()

    /** All admitted retirement facts, including competing replacements; not fresh wire authorization. */
    suspend fun authenticatedRevocations(groupId: String): List<GroupControlFact> = emptyList()

    /**
     * Retains history without changing the checkpoint roster or epoch. The caller must authenticate the
     * payload from a known APPLIED event or its own signed publication; mere key possession is insufficient.
     * Exact replay succeeds, while reuse of an event ID with conflicting meaning throws.
     */
    suspend fun retainAuthenticatedRotationHistory(
        groupId: String,
        rotation: KeyRotation,
        author: String,
        timestamp: Long,
        eventId: String
    ): Boolean = false

    /** Retains the exact authenticated rotation payload, not arrival-derived membership. */
    suspend fun applyAuthenticatedRotation(
        groupId: String,
        rotation: KeyRotation,
        author: String,
        timestamp: Long,
        eventId: String = "",
        expectedMembers: List<String>? = null
    ): Boolean = applyKeyRotation(groupId, rotation.epoch, rotation.members, emptyMap(), expectedMembers)

    /** Competing history must be tied to an already authenticated retirement in this exact epoch. */
    suspend fun hasRevocationInEpoch(groupId: String, author: String, epoch: Int): Boolean = false

    /** Read-only historical authority, never permission for a retired key to self-join. */
    suspend fun isHistoricalCreator(groupId: String, author: String, timestamp: Long, eventId: String): Boolean = false

    /** Maximum of the stored metadata watermark and author name timestamp; unreadable clocks fail closed. */
    suspend fun nameClockFloor(groupId: String, author: String): Long = 0
}
