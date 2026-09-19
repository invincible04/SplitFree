package com.splitfree.data.repository

import com.splitfree.data.local.dao.GroupDao
import com.splitfree.data.local.entities.GroupEntity
import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.model.group.CreatorTransition
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupControlFact
import com.splitfree.domain.model.group.GroupIdentity
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.model.group.GroupProjection
import com.splitfree.domain.model.group.GroupProjectionReducer
import com.splitfree.domain.model.group.KeyRotation
import com.splitfree.domain.model.group.RetiredIdentities
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.SecureStorage
import com.splitfree.domain.util.RelayDefaults
import com.splitfree.util.DebugLog as Log
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Manages group persistence and symmetric key storage.
 *
 * Group metadata (name, members, relays, member display names) is stored in Room. Symmetric group keys
 * are stored separately in Android Keystore-backed encrypted storage and never
 * written to the Room database.
 */
@Singleton
class GroupRepository
@Inject
constructor(
    private val groupDao: GroupDao,
    @Named("groupKeys") private val keyStore: SecureStorage
) : GroupRepositoryContract {
    private val json = Json

    /**
     * Only synchronous key-storage operations run under this lock. Metadata uses SQL compare-and-set
     * instead: a Room transaction may call this repository, so locking across DAO calls can deadlock.
     */
    private val groupLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    private fun lockFor(groupId: String): Mutex = groupLocks.getOrPut(groupId) { Mutex() }
    private val stringListSerializer = ListSerializer(String.serializer())
    private val nameMapSerializer = MapSerializer(
        String.serializer(),
        String.serializer()
    )

    override fun observeAll(): Flow<List<Group>> = groupDao.observeAll().map { entities ->
        entities.map { it.toDomain() }
    }

    override suspend fun getAll(): List<Group> = groupDao.getAll().map { it.toDomain() }

    override suspend fun getById(groupId: String): Group? = groupDao.getById(groupId)?.toDomain()

    override fun observeById(groupId: String): Flow<Group?> = groupDao.observeById(groupId).map { it?.toDomain() }

    /**
     * Returns the key for the group's *current* epoch.
     *
     * The un-epoched key entry stored under the plain `groupId` is only a valid
     * substitute for epoch 0. Falling back to it for a later epoch would silently
     * encrypt post-rotation traffic with a key the removed member still holds, so
     * callers get `null` instead and must treat the group as unusable until the
     * epoch key arrives.
     */
    override suspend fun getGroupKey(groupId: String): String? {
        val group = groupDao.getById(groupId)
        val epoch = group?.keyEpoch ?: 0
        return getGroupKeyForEpoch(groupId, epoch)
    }

    override suspend fun getGroupKeyForEpoch(groupId: String, epoch: Int): String? =
        keyStore.getString("$groupId:$epoch", null)
            ?: if (epoch == 0) keyStore.getString(groupId, null) else null

    override suspend fun getMembers(groupId: String): List<String> = getById(groupId)?.members ?: emptyList()

    /**
     * Removes the un-epoched key and epochs through the stored current epoch. Without a Room row,
     * the sweep is bounded by [MAX_ORPHAN_EPOCH_SWEEP]; keys beyond that range are not removed.
     */
    override suspend fun deleteGroupKey(groupId: String) {
        val maxEpoch = groupDao.getById(groupId)?.keyEpoch ?: MAX_ORPHAN_EPOCH_SWEEP
        lockFor(groupId).withLock {
            keyStore.remove(groupId)
            for (epoch in 0..maxEpoch) keyStore.remove("$groupId:$epoch")
        }
    }

    suspend fun getGroupEntity(groupId: String): GroupEntity? = groupDao.getById(groupId)

    override suspend fun save(group: Group, groupKey: String) {
        val rootKnown = GroupIdentity.matches(group.id, group.originalCreator, group.createdAt)
        require(
            group.creatorTransitions.isEmpty() ||
                CreatorTransition.validate(group.id, group.originalCreator, group.createdAt, group.creatorTransitions)
        )
        require(group.creatorTransitions.all { it.hasAdmissibleTimestamp() })
        val state = GroupProjection(
            checkpoint = group.copy(
                createdBy = if (rootKnown) group.originalCreator else group.createdBy,
                creatorTransitions = emptyList()
            ),
            incomplete = group.createdBy.isEmpty() || group.keyEpoch > 0,
            rootCreator = if (rootKnown) group.originalCreator else "",
            rootCreatedAt = if (rootKnown) group.createdAt else 0,
            creatorTransitions = group.creatorTransitions,
            facts = group.creatorTransitions.map { it.fact() }
        )
        val reduced = GroupProjectionReducer.reduce(state)
        val projected = reduced.group
        // Persist key material before the Room row; a reported storage failure aborts the insert.
        lockFor(group.id).withLock {
            saveEpochKey(group.id, group.keyEpoch, groupKey)
            // Preserve the legacy lookup for epoch 0 only.
            if (group.keyEpoch == 0) keyStore.putString(group.id, groupKey)
        }
        val safeMemberNames = sanitizeMemberNames(projected.memberNames, projected.members)
        groupDao.insert(
            GroupEntity(
                groupId = group.id,
                name = projected.name,
                description = projected.description,
                createdBy = projected.createdBy,
                createdAt = projected.createdAt,
                members = json.encodeToString(stringListSerializer, projected.members),
                relays = json.encodeToString(stringListSerializer, projected.relays),
                memberNames = json.encodeToString(nameMapSerializer, safeMemberNames),
                keyEpoch = projected.keyEpoch,
                memberClocks = json.encodeToString(nameMapSerializer, reduced.clocks),
                lastMetaTimestamp = reduced.timestamp,
                lastMetaEventId = reduced.eventId,
                projectionJson = json.encodeToString(GroupProjection.serializer(), state)
            )
        )
    }

    /**
     * Store the key for [epoch]. Throws [com.splitfree.domain.repository.SecureStorageException]
     * if the write cannot be durably committed; callers must not advance the group's epoch
     * or publish rotation events until this returns.
     *
     * Epoch key material is immutable: storing the same key again is a no-op, and a different key for
     * an epoch that already has one is refused with [IllegalStateException]. Two keys for one epoch
     * would mean two rotations claim it, and silently replacing one would strand whoever holds the other.
     */
    override suspend fun saveGroupKeyForEpoch(groupId: String, epoch: Int, groupKey: String) {
        lockFor(groupId).withLock { saveEpochKey(groupId, epoch, groupKey) }
    }

    private fun saveEpochKey(groupId: String, epoch: Int, groupKey: String) {
        val existing = keyStore.getString("$groupId:$epoch", null)
            ?: if (epoch == 0) keyStore.getString(groupId, null) else null
        if (existing != null) {
            check(existing == groupKey) { "Epoch $epoch of ${groupId.take(8)} already has different key material" }
            return
        }
        keyStore.putString("$groupId:$epoch", groupKey)
    }

    override suspend fun updateKeyEpoch(groupId: String, epoch: Int) {
        require(epoch >= 0)
        updateGroup(groupId) { row ->
            if (epoch <= row.keyEpoch) return@updateGroup false
            val group = row.toDomain()
            // Key possession from a backup is a checkpoint, not a signed rotation roster.
            appendFact(
                row,
                GroupControlFact(
                    "key-checkpoint:$epoch",
                    "checkpoint",
                    0,
                    epoch,
                    meta = GroupMeta(members = group.members, memberNames = group.memberNames)
                ),
                successfulNoOp = true
            )
        }
    }

    override suspend fun updateCreator(groupId: String, createdBy: String, createdAt: Long) {
        require(createdBy.isNotEmpty()) { "createdBy must not be empty" }
        updateGroup(groupId) { entity ->
            if (entity.createdBy.isNotEmpty()) return@updateGroup false
            val clocks = decodeMap(entity.memberClocks, groupId, "memberClocks")
            // The creator's own revocation may have been applied before their metadata arrived (a
            // restore in clock order, a skewed clock): the role then belongs to where their chain ends.
            val creator = resolveRoster(listOf(createdBy), clocks).members.singleOrNull()
                ?: return@updateGroup false
            if (creator != createdBy) {
                Log.i(
                    TAG,
                    "Creator ${createdBy.take(8)} of ${groupId.take(8)} was replaced; recording ${creator.take(8)}"
                )
            }
            groupDao.updateCreator(groupId, creator, createdAt, entity.memberClocks)
                .let { if (it == 1) true else null }
        }
    }

    override suspend fun updateLastSync(groupId: String, timestamp: Long) {
        groupDao.updateLastSync(groupId, timestamp)
    }

    override suspend fun updateFromMeta(
        groupId: String,
        name: String,
        members: List<String>,
        relays: List<String>,
        eventTimestamp: Long,
        createdBy: String,
        memberNames: Map<String, String>,
        description: String?,
        eventId: String,
        applyRoster: Boolean,
        expectedKeyEpoch: Int?,
        expectedCreator: String?
    ): Boolean {
        require(eventTimestamp > 0) { "group_meta needs the event's created_at to be ordered" }
        if (members.size > RelayDefaults.MAX_GROUP_MEMBERS) {
            Log.w(
                TAG,
                "Rejecting group_meta with ${members.size} members (max ${RelayDefaults.MAX_GROUP_MEMBERS})"
            )
            return false
        }
        val safeRelays = relays.filter(InviteLinkCodec::relayFits)
        if (!InviteLinkCodec.fitsInviteLink(safeRelays)) {
            Log.w(TAG, "Rejecting group_meta whose relays do not fit in an invite link")
            return false
        }
        return updateGroup(groupId) { entity ->
            if (expectedCreator != null && entity.createdBy != expectedCreator) return@updateGroup false
            if (entity.projectionJson.isNotEmpty()) {
                val fact = GroupControlFact(
                    eventId,
                    "meta",
                    eventTimestamp,
                    expectedKeyEpoch ?: entity.keyEpoch,
                    author = expectedCreator ?: entity.createdBy,
                    meta = GroupMeta(
                        name,
                        description ?: entity.description,
                        createdBy,
                        entity.createdAt,
                        members,
                        safeRelays,
                        memberNames,
                        expectedKeyEpoch ?: entity.keyEpoch
                    ),
                    roster = applyRoster,
                    trusted = expectedCreator == null
                )
                return@updateGroup appendFact(entity, fact, successfulNoOp = false)
            }
            if (!isNewerClock(eventTimestamp, eventId, entity.lastMetaTimestamp to entity.lastMetaEventId)) {
                return@updateGroup false
            }
            val storedMembers = decodeList(entity.members, groupId, "members")
            val canApplyRoster = applyRoster && (expectedKeyEpoch == null || expectedKeyEpoch == entity.keyEpoch)
            val clocks = decodeMap(entity.memberClocks, groupId, "memberClocks")
            if (expectedCreator != null && "revoked:$expectedCreator" in clocks) return@updateGroup false
            if (createdBy.isNotEmpty() && "revoked:$createdBy" in clocks) return@updateGroup false
            // The creator may not have seen a revocation this device applied: its roster names the old
            // key, which becomes the recorded replacement here rather than resurrecting the tombstone.
            val resolved = resolveRoster(members, clocks)
            val finalMembers = if (canApplyRoster) resolved.members else storedMembers
            if (!canApplyRoster) {
                Log.i(TAG, "group_meta ${eventId.take(8)} predates the current key epoch; roster kept")
            }
            val incomingNames = resolved.remapNames(memberNames)
            val storedNames = decodeMap(entity.memberNames, groupId, "memberNames")
            // Per member: the creator's map wins unless that member's own rename is newer.
            val merged = LinkedHashMap<String, String>()
            for (member in finalMembers) {
                val ownClock = clocks[member]?.let(::parseMemberClock)
                val memberIsNewer =
                    ownClock != null && isNewerClock(ownClock.first, ownClock.second, eventTimestamp to eventId)
                val chosen = if (memberIsNewer) storedNames[member] else incomingNames[member]
                if (chosen != null) merged[member] = chosen
            }
            // The conditional statement is kept as the final guard: the watermark, roster and names
            // land together or not at all, even if another writer slipped in between read and write.
            groupDao.updateMetaIfNewer(
                groupId,
                name,
                json.encodeToString(stringListSerializer, finalMembers),
                json.encodeToString(stringListSerializer, safeRelays),
                createdBy,
                eventTimestamp,
                json.encodeToString(nameMapSerializer, sanitizeMemberNames(merged, finalMembers)),
                description,
                eventId,
                expectedKeyEpoch = entity.keyEpoch,
                expectedMembers = entity.members,
                expectedMemberNames = entity.memberNames,
                expectedMemberClocks = entity.memberClocks,
                expectedMetaTimestamp = entity.lastMetaTimestamp,
                expectedMetaEventId = entity.lastMetaEventId,
                expectedCreatedBy = entity.createdBy
            ).let { if (it == 1) true else null }
        }
    }

    override suspend fun applyKeyRotation(
        groupId: String,
        epoch: Int,
        members: List<String>,
        memberNames: Map<String, String>,
        expectedMembers: List<String>?
    ): Boolean {
        if (members.size > RelayDefaults.MAX_GROUP_MEMBERS) {
            Log.w(TAG, "Rejecting key_rotation with ${members.size} members (max ${RelayDefaults.MAX_GROUP_MEMBERS})")
            return false
        }
        return updateGroup(groupId) { entity ->
            if (epoch <= entity.keyEpoch) return@updateGroup false
            val storedMembers = decodeList(entity.members, groupId, "members")
            if (expectedMembers != null && storedMembers != expectedMembers) return@updateGroup false
            if (entity.projectionJson.isNotEmpty()) {
                return@updateGroup appendFact(
                    entity,
                    GroupControlFact(
                        "rotation:$epoch",
                        "rotation",
                        0,
                        epoch,
                        meta = GroupMeta(members = members, memberNames = memberNames)
                    ),
                    successfulNoOp = false
                )
            }
            val clocks = decodeMap(entity.memberClocks, groupId, "memberClocks")
            // Resolve stale roster identities so a known revocation does not block epoch advancement.
            val resolved = resolveRoster(members, clocks)
            if (resolved.members != members) {
                Log.i(TAG, "key_rotation to epoch $epoch of ${groupId.take(8)} named revoked identities; resolved")
            }
            groupDao.applyKeyRotation(
                groupId,
                epoch,
                json.encodeToString(stringListSerializer, resolved.members),
                json.encodeToString(
                    nameMapSerializer,
                    sanitizeMemberNames(resolved.remapNames(memberNames), resolved.members)
                ),
                expectedMembers = entity.members,
                expectedKeyEpoch = entity.keyEpoch,
                expectedMemberClocks = entity.memberClocks,
                expectedCreatedBy = entity.createdBy
            ).let { if (it == 1) true else null }
        }
    }

    override suspend fun overrideMembership(
        groupId: String,
        members: List<String>,
        memberNames: Map<String, String>,
        createdBy: String,
        eventTimestamp: Long,
        eventId: String
    ) {
        if (members.size > RelayDefaults.MAX_GROUP_MEMBERS) {
            Log.w(TAG, "Rejecting membership override with ${members.size} members")
            return
        }
        groupDao.overrideMembership(
            groupId,
            json.encodeToString(stringListSerializer, members),
            json.encodeToString(nameMapSerializer, sanitizeMemberNames(memberNames, members)),
            createdBy,
            eventTimestamp,
            eventId
        )
    }

    override suspend fun applyIdentityRevocation(
        groupId: String,
        oldPubkey: String,
        newPubkey: String,
        eventTimestamp: Long,
        eventId: String,
        allowAbsent: Boolean,
        successorProven: Boolean
    ): Boolean {
        require(oldPubkey.isNotEmpty() && oldPubkey != newPubkey) { "Revocation needs distinct identities" }
        require(eventTimestamp > 0) { "Revocation needs the event's created_at to be ordered" }
        val successor = newPubkey.takeIf { it.isNotEmpty() }
        return updateGroup(groupId) { entity ->
            val clocks = decodeMap(entity.memberClocks, groupId, "memberClocks")
            val members = decodeList(entity.members, groupId, "members")
            if ("revoked:$oldPubkey" !in clocks && oldPubkey !in members && successor !in members && !allowAbsent) {
                return@updateGroup false
            }
            appendFact(
                entity,
                GroupControlFact(
                    eventId,
                    "revocation",
                    eventTimestamp,
                    entity.keyEpoch,
                    author = oldPubkey,
                    successor = newPubkey,
                    proven = successorProven
                ),
                successfulNoOp = true
            )
        }
    }

    override suspend fun applyMemberSelfUpdate(
        groupId: String,
        author: String,
        eventTimestamp: Long,
        eventId: String,
        join: Boolean,
        displayName: String?,
        expectedKeyEpoch: Int?
    ): Boolean {
        require(eventTimestamp > 0) { "member update needs the event's created_at to be ordered" }
        var authorWasPresent = false
        return updateGroup(groupId) { entity ->
            val members = decodeList(entity.members, groupId, "members")
            // A concurrent same-epoch revocation must not turn a rename into a fresh self-join.
            if (authorWasPresent && author !in members) return@updateGroup false
            authorWasPresent = author in members
            if (author !in members && (!join || (expectedKeyEpoch != null && expectedKeyEpoch != entity.keyEpoch))) {
                return@updateGroup false
            }
            val clocks = decodeMap(entity.memberClocks, groupId, "memberClocks")
            if ("revoked:$author" in clocks) return@updateGroup false
            if (entity.projectionJson.isNotEmpty()) {
                return@updateGroup appendFact(
                    entity,
                    GroupControlFact(
                        eventId,
                        "self",
                        eventTimestamp,
                        expectedKeyEpoch ?: entity.keyEpoch,
                        author = author,
                        join = join,
                        displayName = displayName
                    ),
                    successfulNoOp = false
                )
            }
            val ownClock = clocks[author]?.let(::parseMemberClock)
            // Names and joins are independent registers; a null name must not hide an older rename.
            val joinClockKey = "join:$author"
            val joinClock = clocks[joinClockKey]?.let(::parseMemberClock)
            val applyJoin = join && (joinClock == null || isNewerClock(eventTimestamp, eventId, joinClock))
            val applyName = displayName != null &&
                (ownClock == null || isNewerClock(eventTimestamp, eventId, ownClock))
            if (!applyJoin && !applyName) return@updateGroup false

            val newMembers = if (applyJoin && author !in members) members + author else members
            if (author !in newMembers) return@updateGroup false
            if (newMembers.size > RelayDefaults.MAX_GROUP_MEMBERS) {
                Log.w(
                    TAG,
                    "Rejecting self-join to $groupId: ${newMembers.size} members (max ${RelayDefaults.MAX_GROUP_MEMBERS})"
                )
                return@updateGroup false
            }

            val names = decodeMap(entity.memberNames, groupId, "memberNames").toMutableMap()
            if (applyName &&
                isNewerClock(eventTimestamp, eventId, entity.lastMetaTimestamp to entity.lastMetaEventId)
            ) {
                val safeName = checkNotNull(displayName).trim().take(MAX_DISPLAY_NAME_LENGTH)
                if (safeName.isEmpty()) names.remove(author) else names[author] = safeName
            }
            val newClocks = clocks.toMutableMap().apply {
                if (applyName) put(author, "$eventTimestamp:$eventId")
                if (applyJoin) put(joinClockKey, "$eventTimestamp:$eventId")
            }

            groupDao.updateMemberSelf(
                groupId,
                json.encodeToString(stringListSerializer, newMembers),
                json.encodeToString(nameMapSerializer, sanitizeMemberNames(names, newMembers)),
                json.encodeToString(nameMapSerializer, newClocks),
                expectedKeyEpoch = entity.keyEpoch,
                expectedMembers = entity.members,
                expectedMemberNames = entity.memberNames,
                expectedMemberClocks = entity.memberClocks,
                expectedMetaTimestamp = entity.lastMetaTimestamp,
                expectedMetaEventId = entity.lastMetaEventId,
                expectedCreatedBy = entity.createdBy
            ).let { if (it == 1) true else null }
        }
    }

    override suspend fun resolveRoster(groupId: String, members: List<String>): List<String> {
        val entity = groupDao.getById(groupId) ?: return members
        return resolveRoster(members, decodeMap(entity.memberClocks, groupId, "memberClocks")).members
    }

    override suspend fun retiredIdentities(groupId: String): RetiredIdentities {
        val entity = groupDao.getById(groupId) ?: return RetiredIdentities.NONE
        return retiredIdentities(decodeMap(entity.memberClocks, groupId, "memberClocks"))
    }

    /**
     * Only proven `succeeded:` links move balances; roster-only `replaced:` links are insufficient.
     * A cut chain retains obligations at its terminal revoked identity. A loop or overlong chain
     * records no successor, leaving the balance on the original key rather than an arbitrary hop.
     */
    private fun retiredIdentities(clocks: Map<String, String>): RetiredIdentities {
        val revoked = clocks.keys.filter { it.startsWith(REVOKED_PREFIX) }.mapTo(HashSet()) {
            it.removePrefix(REVOKED_PREFIX)
        }
        if (revoked.isEmpty()) return RetiredIdentities.NONE
        val successors = HashMap<String, String>()
        for (old in revoked) {
            val end = followChain(old, clocks, SUCCEEDED_PREFIX) ?: continue
            if (end != old) successors[old] = end
        }
        return RetiredIdentities(revoked, successors)
    }

    /**
     * Follows [linkPrefix] links only while the current identity is tombstoned. Returns the live
     * endpoint or a revoked identity with no link; null rejects loops and overlong chains.
     */
    private fun followChain(start: String, clocks: Map<String, String>, linkPrefix: String): String? {
        var current = start
        val seen = HashSet<String>()
        while ("$REVOKED_PREFIX$current" in clocks) {
            if (!seen.add(current) || seen.size > MAX_CHAIN_LENGTH) return null
            val next = clocks["$linkPrefix$current"]
            if (next.isNullOrEmpty()) break
            current = next
        }
        return current
    }

    /** A roster with tombstoned identities replaced or dropped, plus how each original identity moved. */
    private class ResolvedRoster(val members: List<String>, private val moved: Map<String, String>) {
        /** Names keyed by the original identities, re-keyed to where they resolved; explicit entries win. */
        fun remapNames(names: Map<String, String>): Map<String, String> {
            if (moved.isEmpty()) return names
            val out = LinkedHashMap<String, String>()
            for ((member, name) in names) if (member !in moved) out[member] = name
            for ((member, name) in names) {
                val target = moved[member] ?: continue
                if (target.isNotEmpty() && target !in out) out[target] = name
            }
            return out
        }
    }

    /**
     * Seats each of [members] at the live end of its `replaced:` chain. A tombstoned identity whose chain is
     * cut (no replacement), ends on another tombstoned identity, loops or exceeds [MAX_CHAIN_LENGTH] has no
     * live seat and drops out; an intermediate identity is never seated.
     */
    private fun resolveRoster(members: List<String>, clocks: Map<String, String>): ResolvedRoster {
        val moved = LinkedHashMap<String, String>()
        val resolved = ArrayList<String>(members.size)
        for (member in members) {
            val end = followChain(member, clocks, REPLACED_PREFIX)
            if (end == null || end.isEmpty() || "$REVOKED_PREFIX$end" in clocks) {
                moved[member] = ""
                continue
            }
            if (end != member) moved[member] = end
            if (end !in resolved) resolved += end
        }
        return ResolvedRoster(resolved, moved)
    }

    override suspend fun resetRosterProjection(groupId: String, eventTimestamp: Long, eventId: String): Boolean {
        val reset = groupDao.resetRosterProjection(groupId, eventTimestamp, eventId) == 1
        if (reset) {
            Log.i(
                TAG,
                "Roster projection of ${groupId.take(8)} reset: history older than its watermark arrived " +
                    "(${eventId.take(8)} at $eventTimestamp)"
            )
        }
        return reset
    }

    override suspend fun hasCanonicalProjection(groupId: String): Boolean = groupDao.getById(groupId) != null

    override suspend fun nameClockFloor(groupId: String, author: String): Long {
        val row = groupDao.getById(groupId) ?: return 0
        val clocks = json.decodeFromString(nameMapSerializer, row.memberClocks)
        val own =
            clocks[author]?.let { checkNotNull(parseMemberClock(it)) { "Unreadable display-name clock" }.first } ?: 0
        return maxOf(row.lastMetaTimestamp, own)
    }

    private fun projection(row: GroupEntity): GroupProjection = if (row.projectionJson.isNotEmpty()) {
        json.decodeFromString(GroupProjection.serializer(), row.projectionJson)
    } else {
        // A legacy checkpoint is explicitly incomplete. Its epoch and tombstones cannot be reset
        // merely because a partial backup lacks the signed events that originally established them.
        GroupProjection(
            row.toDomain(),
            json.decodeFromString(nameMapSerializer, row.memberClocks),
            row.lastMetaTimestamp,
            row.lastMetaEventId,
            incomplete = true
        )
    }

    override suspend fun previewCreatorBootstrap(
        groupId: String,
        originalCreator: String,
        createdAt: Long,
        transitions: List<CreatorTransition>
    ): Group? {
        val row = groupDao.getById(groupId) ?: return null
        val next = mergeBootstrap(projection(row), originalCreator, createdAt, transitions) ?: return null
        return GroupProjectionReducer.reduce(next).group
    }

    override suspend fun mergeCreatorBootstrap(
        groupId: String,
        originalCreator: String,
        createdAt: Long,
        transitions: List<CreatorTransition>
    ): Boolean = updateGroup(groupId) { row ->
        val state = projection(row)
        val next = mergeBootstrap(state, originalCreator, createdAt, transitions) ?: return@updateGroup false
        if (next == state) true else writeProjection(row, next, successfulNoOp = true)
    }

    private fun mergeBootstrap(
        state: GroupProjection,
        originalCreator: String,
        createdAt: Long,
        transitions: List<CreatorTransition>
    ): GroupProjection? {
        if (!CreatorTransition.validate(state.checkpoint.id, originalCreator, createdAt, transitions)) return null
        val root = state.verifiedRoot()
        if (root != null && root != (originalCreator to createdAt)) return null
        val facts = state.facts.toMutableList()
        val proofs = state.creatorTransitions.toMutableList()
        for (transition in transitions) {
            val fact = transition.fact()
            val existing = facts.firstOrNull { it.id == fact.id }
            if (existing != null && existing != fact) return null
            if (proofs.any { it.eventId == transition.eventId && it.fact() != fact }) return null
            // Retained facts remain valid after clock rollback; only new evidence needs admission.
            if (existing == null && !transition.hasAdmissibleTimestamp()) return null
            if (existing == null) facts += fact
            if (proofs.none { it.eventId == transition.eventId }) proofs += transition
        }
        if (proofs.size > CreatorTransition.MAX_TRANSITIONS) return null
        return state.copy(
            rootCreator = originalCreator,
            rootCreatedAt = createdAt,
            creatorTransitions = proofs.sortedWith(compareBy({ it.timestamp }, { it.eventId })),
            facts = facts
        )
    }

    override suspend fun applyAuthenticatedRevocation(
        groupId: String,
        oldPubkey: String,
        newPubkey: String,
        timestamp: Long,
        eventId: String,
        epoch: Int,
        successorProven: Boolean
    ): Boolean = updateGroup(groupId) { row ->
        require(oldPubkey.isNotEmpty() && oldPubkey != newPubkey && timestamp > 0)
        appendFact(
            row,
            GroupControlFact(
                eventId,
                "revocation",
                timestamp,
                epoch,
                author = oldPubkey,
                successor = newPubkey,
                proven = successorProven
            ),
            successfulNoOp = true
        )
    }

    override suspend fun hasRevocationInEpoch(groupId: String, author: String, epoch: Int): Boolean {
        val row = groupDao.getById(groupId) ?: return false
        return projection(row).facts.any { it.kind == "revocation" && it.author == author && it.epoch == epoch }
    }

    override suspend fun isHistoricalCreator(
        groupId: String,
        author: String,
        timestamp: Long,
        eventId: String
    ): Boolean {
        val row = groupDao.getById(groupId) ?: return false
        val state = projection(row)
        val earlier = state.facts.filter { it.timestamp < timestamp || (it.timestamp == timestamp && it.id < eventId) }
        return GroupProjectionReducer.reduce(state.copy(facts = earlier)).group.createdBy == author
    }

    override suspend fun applyAuthenticatedMeta(
        groupId: String,
        meta: GroupMeta,
        author: String,
        timestamp: Long,
        eventId: String,
        epoch: Int,
        expectedGroup: Group?
    ): Boolean {
        if (timestamp <= 0 ||
            meta.members.isEmpty() ||
            meta.members.size > RelayDefaults.MAX_GROUP_MEMBERS
        ) {
            return false
        }
        val safeRelays = meta.relays.filter(InviteLinkCodec::relayFits)
        if (!InviteLinkCodec.fitsInviteLink(safeRelays)) return false
        return updateGroup(groupId) { row ->
            val guardedJoin = expectedGroup != null && expectedGroup.createdBy != author
            if (expectedGroup != null) {
                if (row.toDomain() != expectedGroup || row.keyEpoch != epoch) return@updateGroup false
                if (guardedJoin) {
                    val clocks = decodeMap(row.memberClocks, groupId, "memberClocks")
                    if ("revoked:$author" in clocks ||
                        author !in meta.members ||
                        meta.keyEpoch != epoch ||
                        meta.originalCreator != expectedGroup.originalCreator ||
                        meta.createdBy != expectedGroup.createdBy ||
                        meta.createdAt != expectedGroup.createdAt ||
                        meta.creatorTransitions != expectedGroup.creatorTransitions ||
                        (meta.members.toSet() - expectedGroup.members.toSet() - author).isNotEmpty()
                    ) {
                        return@updateGroup false
                    }
                } else if (!isNewerClock(timestamp, eventId, row.lastMetaTimestamp to row.lastMetaEventId)) {
                    return@updateGroup false
                }
            }
            val state = projection(row)
            val next = if (meta.originalCreator.isNotEmpty() || meta.creatorTransitions.isNotEmpty()) {
                mergeBootstrap(state, meta.originalCreator, meta.createdAt, meta.creatorTransitions)
                    ?: return@updateGroup false
            } else {
                state
            }
            val fact = GroupControlFact(
                eventId,
                "meta",
                timestamp,
                epoch,
                author = author,
                meta = meta.copy(relays = safeRelays)
            )
            val canonical = canonicalizeLegacyJoin(next, fact)
            if (guardedJoin) {
                val result = GroupProjectionReducer.reduce(canonical.copy(facts = canonical.facts + fact))
                if (author !in result.group.members || "revoked:$author" in result.clocks) return@updateGroup false
            }
            appendFact(row, fact, successfulNoOp = expectedGroup == null || guardedJoin, state = canonical)
        }
    }

    override suspend fun authenticatedRevocations(groupId: String): List<GroupControlFact> {
        val row = groupDao.getById(groupId) ?: return emptyList()
        return projection(row).facts.filter { it.kind == "revocation" }
    }

    override suspend fun authenticatedRotations(groupId: String): Map<String, KeyRotation> {
        val row = groupDao.getById(groupId) ?: return emptyMap()
        return projection(row).facts.filter { it.kind in setOf("rotation", "rotation-history") && it.rotation != null }
            .associate { it.id to checkNotNull(it.rotation) }
    }

    override suspend fun retainAuthenticatedRotationHistory(
        groupId: String,
        rotation: KeyRotation,
        author: String,
        timestamp: Long,
        eventId: String
    ): Boolean = updateGroup(groupId) { row ->
        if (eventId.isEmpty() ||
            rotation.epoch <= 0 ||
            timestamp <= 0 ||
            rotation.members.size > RelayDefaults.MAX_GROUP_MEMBERS
        ) {
            return@updateGroup false
        }
        val fact = GroupControlFact(
            eventId,
            "rotation-history",
            timestamp,
            rotation.epoch,
            author = author,
            rotation = rotation
        )
        val existing = projection(row).facts.firstOrNull { it.id == eventId }
        if (existing?.kind == "rotation") {
            check(existing == fact.copy(kind = "rotation")) { "Authenticated control event changed its meaning" }
            true
        } else {
            appendFact(row, fact, successfulNoOp = true)
        }
    }

    override suspend fun applyAuthenticatedRotation(
        groupId: String,
        rotation: KeyRotation,
        author: String,
        timestamp: Long,
        eventId: String,
        expectedMembers: List<String>?
    ): Boolean = updateGroup(groupId) { row ->
        if (rotation.epoch <= 0 ||
            timestamp <= 0 ||
            rotation.members.size > RelayDefaults.MAX_GROUP_MEMBERS
        ) {
            return@updateGroup false
        }
        if (expectedMembers != null && row.toDomain().members != expectedMembers) return@updateGroup false
        val fact = GroupControlFact(
            eventId.ifEmpty { "rotation:${rotation.epoch}:$author:$timestamp" },
            "rotation",
            timestamp,
            rotation.epoch,
            author = author,
            rotation = rotation
        )
        val state = projection(row)
        val existing = state.facts.firstOrNull { it.id == fact.id }
        if (existing?.kind == "rotation-history") {
            check(existing == fact.copy(kind = "rotation-history")) {
                "Authenticated control event changed its meaning"
            }
            val next = state.copy(facts = state.facts.map { if (it.id == fact.id) fact else it })
            if (GroupProjectionReducer.reduce(next) == GroupProjectionReducer.reduce(state)) {
                writeProjection(row, next, true)
            } else {
                true
            }
        } else {
            appendFact(row, fact, successfulNoOp = true, state = state)
        }
    }

    private fun canonicalizeLegacyJoin(state: GroupProjection, fact: GroupControlFact): GroupProjection {
        val old = state.facts.firstOrNull { it.id == fact.id } ?: return state
        if (old.kind != "self" || fact.id.length != 64 || fact.id.any { it !in "0123456789abcdef" }) return state
        val meta = checkNotNull(fact.meta)
        val expected = GroupControlFact(
            fact.id,
            "self",
            fact.timestamp,
            fact.epoch,
            author = fact.author,
            join = true,
            displayName = meta.memberNames[fact.author]?.takeIf { it.isNotBlank() }
        )
        if (old != expected || fact.author !in meta.members || meta.keyEpoch != fact.epoch) return state
        val canonical = state.copy(facts = state.facts.map { if (it.id == fact.id) fact else it })
        val before = GroupProjectionReducer.reduce(state)
        val after = GroupProjectionReducer.reduce(canonical)
        // Pre-fix joins kept only their self fields. Upgrade authenticated replay only when it preserves
        // their entire projected meaning, apart from the obsolete local-only join clock.
        val joinClock = "join:${fact.author}"
        return if (before.copy(clocks = before.clocks - joinClock) == after.copy(clocks = after.clocks - joinClock)) {
            canonical
        } else {
            state
        }
    }

    /** Null means the row changed concurrently: re-read and recompute, without acquiring a Room-crossing mutex. */
    private suspend fun appendFact(
        row: GroupEntity,
        fact: GroupControlFact,
        successfulNoOp: Boolean,
        state: GroupProjection = projection(row)
    ): Boolean? {
        // Local retries share a kind/author/timestamp ID; conflicting facts with that ID are rejected.
        val normalized = if (fact.id.isEmpty()) {
            fact.copy(
                id = "local:${fact.kind}:${fact.author}:${fact.timestamp}"
            )
        } else {
            fact
        }
        val existing = state.facts.firstOrNull { it.id == normalized.id }
        if (existing != null) {
            check(existing == normalized) { "Authenticated control event changed its meaning" }
            return if (state == projection(row)) successfulNoOp else writeProjection(row, state, successfulNoOp)
        }
        val next = state.copy(
            facts = state.facts + normalized,
            incomplete = state.incomplete || normalized.kind == "checkpoint"
        )
        return writeProjection(row, next, successfulNoOp)
    }

    private suspend fun writeProjection(row: GroupEntity, next: GroupProjection, successfulNoOp: Boolean): Boolean? {
        val result = GroupProjectionReducer.reduce(next)
        val group = result.group
        if (group.members.size > RelayDefaults.MAX_GROUP_MEMBERS) return false
        val changed = row.members != json.encodeToString(stringListSerializer, group.members) ||
            row.memberNames != json.encodeToString(nameMapSerializer, group.memberNames) ||
            row.memberClocks != json.encodeToString(nameMapSerializer, result.clocks) ||
            row.createdBy != group.createdBy ||
            row.name != group.name ||
            row.description != group.description ||
            row.relays != json.encodeToString(stringListSerializer, group.relays) ||
            row.keyEpoch != group.keyEpoch ||
            row.lastMetaTimestamp != result.timestamp ||
            row.lastMetaEventId != result.eventId
        val wrote = groupDao.writeProjection(
            row.groupId, group.name, group.description,
            json.encodeToString(
                stringListSerializer,
                group.members
            ),
            json.encodeToString(nameMapSerializer, group.memberNames),
            json.encodeToString(nameMapSerializer, result.clocks), group.createdBy, group.createdAt,
            json.encodeToString(stringListSerializer, group.relays), group.keyEpoch, result.timestamp, result.eventId,
            json.encodeToString(GroupProjection.serializer(), next), row.projectionJson, row.keyEpoch,
            row.members, row.memberClocks, row.memberNames, row.createdBy, row.lastMetaTimestamp, row.lastMetaEventId
        )
        return if (wrote == 1) changed || successfulNoOp else null
    }

    private suspend fun updateGroup(groupId: String, update: suspend (GroupEntity) -> Boolean?): Boolean {
        var entity = groupDao.getById(groupId) ?: return false
        while (true) {
            update(entity)?.let { return it }
            val latest = groupDao.getById(groupId) ?: return false
            if (latest == entity) return false
            entity = latest
        }
    }

    /** `"createdAt:eventId"` -> `(createdAt, eventId)`, or null when the stored value is unreadable. */
    private fun parseMemberClock(raw: String): Pair<Long, String>? {
        val sep = raw.indexOf(':')
        if (sep < 0) return null
        val ts = raw.substring(0, sep).toLongOrNull() ?: return null
        return ts to raw.substring(sep + 1)
    }

    /** Strict tuple order on `(timestamp, eventId)`; equal clocks are not newer (idempotent replay). */
    private fun isNewerClock(timestamp: Long, eventId: String, stored: Pair<Long, String>): Boolean =
        timestamp > stored.first || (timestamp == stored.first && eventId > stored.second)

    private fun decodeList(raw: String, groupId: String, column: String): List<String> = try {
        json.decodeFromString(stringListSerializer, raw)
    } catch (e: Exception) {
        Log.w(TAG, "Group $groupId has unreadable $column JSON; treating as empty: ${e.javaClass.simpleName}")
        emptyList()
    }

    private fun decodeMap(raw: String, groupId: String, column: String): Map<String, String> = try {
        json.decodeFromString(nameMapSerializer, raw)
    } catch (e: Exception) {
        Log.w(TAG, "Group $groupId has unreadable $column JSON; treating as empty: ${e.javaClass.simpleName}")
        emptyMap()
    }

    private fun GroupEntity.toDomain(): Group {
        val decodedMembers = decodeList(members, groupId, "members")
        val decodedRelays = decodeList(relays, groupId, "relays")
        val decodedNames = decodeMap(memberNames, groupId, "memberNames")
        val state = if (projectionJson.isNotEmpty()) json.decodeFromString<GroupProjection>(projectionJson) else null
        val root = state?.verifiedRoot()
            ?: (createdBy to createdAt).takeIf { GroupIdentity.matches(groupId, createdBy, createdAt) }
        return Group(
            id = groupId,
            name = name,
            description = description,
            createdBy = createdBy,
            createdAt = createdAt,
            members = decodedMembers,
            relays = decodedRelays,
            memberNames = sanitizeMemberNames(decodedNames, decodedMembers),
            keyEpoch = keyEpoch,
            originalCreator = root?.first.orEmpty(),
            creatorTransitions = state?.verifiedCreatorTransitions().orEmpty()
        )
    }

    private fun sanitizeMemberNames(names: Map<String, String>, members: List<String>): Map<String, String> {
        if (names.isEmpty() || members.isEmpty()) return emptyMap()
        val memberSet = members.toSet()
        return names.entries
            .asSequence()
            .filter { it.key in memberSet }
            .map { it.key to it.value.trim().take(MAX_DISPLAY_NAME_LENGTH) }
            .filter { it.second.isNotEmpty() }
            .toMap()
    }

    companion object {
        private const val TAG = "GroupRepository"

        /** Display names are trimmed and truncated to this many characters before persisting. */
        private const val MAX_DISPLAY_NAME_LENGTH = 50

        /** Bounds orphan cleanup when the current epoch is unknown; higher epoch keys may remain. */
        private const val MAX_ORPHAN_EPOCH_SWEEP = 64

        /** `memberClocks` entry recording which identity replaced a tombstoned one. */
        private const val REPLACED_PREFIX = "replaced:"

        /** `memberClocks` entry recording that an identity was revoked, keyed by its pubkey. */
        private const val REVOKED_PREFIX = "revoked:"

        /** Successor-authorized link used to reattribute balances, unlike a roster-only replacement. */
        private const val SUCCEEDED_PREFIX = "succeeded:"

        /** Maximum tombstoned identities visited; exceeding it rejects the chain rather than choosing a hop. */
        private const val MAX_CHAIN_LENGTH = 1024
    }
}
