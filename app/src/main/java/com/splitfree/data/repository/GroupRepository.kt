package com.splitfree.data.repository

import com.splitfree.data.local.dao.GroupDao
import com.splitfree.data.local.entities.GroupEntity
import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.model.group.Group
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
     * Remove every key stored for [groupId]: the un-epoched entry under the plain `groupId` plus `"$groupId:$epoch"`
     * for each epoch up to the group's current one. If the Room row is already gone the epoch is
     * unknown, so epochs `0..MAX_ORPHAN_EPOCH_SWEEP` are swept instead.
     *
     * No caller yet. A "leave group" flow must call it so
     * the device stops being able to decrypt a group it no longer belongs to.
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
        // Persist the key FIRST. SecureStorage.putString commits synchronously and throws
        // SecureStorageException on failure, so if the key cannot be stored we never reach
        // groupDao.insert and no Room row exists without a recoverable key behind it.
        lockFor(group.id).withLock {
            saveEpochKey(group.id, group.keyEpoch, groupKey)
            // At epoch 0 the key is also stored under the plain groupId, the un-epoched entry
            // that getGroupKeyForEpoch accepts for epoch 0 only.
            if (group.keyEpoch == 0) keyStore.putString(group.id, groupKey)
        }
        val safeMemberNames = sanitizeMemberNames(group.memberNames, group.members)
        groupDao.insert(
            GroupEntity(
                groupId = group.id,
                name = group.name,
                description = group.description,
                createdBy = group.createdBy,
                createdAt = group.createdAt,
                members = json.encodeToString(stringListSerializer, group.members),
                relays = json.encodeToString(stringListSerializer, group.relays),
                memberNames = json.encodeToString(nameMapSerializer, safeMemberNames),
                keyEpoch = group.keyEpoch
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
        groupDao.updateKeyEpoch(groupId, epoch)
    }

    override suspend fun updateCreator(groupId: String, createdBy: String, createdAt: Long) {
        require(createdBy.isNotEmpty()) { "createdBy must not be empty" }
        updateGroup(groupId) { entity ->
            if (entity.createdBy.isNotEmpty()) return@updateGroup false
            val clocks = decodeMap(entity.memberClocks, groupId, "memberClocks")
            if ("revoked:$createdBy" in clocks) return@updateGroup false
            groupDao.updateCreator(groupId, createdBy, createdAt, entity.memberClocks)
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
            val clocks = decodeMap(entity.memberClocks, groupId, "memberClocks")
            // A rotation authored before its creator saw a revocation still names the revoked key.
            // Installing the recorded replacement keeps this device on the new epoch; refusing the
            // rotation would leave it behind for good (every later epoch is then a gap).
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
        allowAbsent: Boolean
    ): Boolean {
        require(oldPubkey.isNotEmpty() && oldPubkey != newPubkey) { "Revocation needs distinct identities" }
        require(eventTimestamp > 0) { "Revocation needs the event's created_at to be ordered" }
        return updateGroup(groupId) { entity ->
            val clocks = decodeMap(entity.memberClocks, groupId, "memberClocks")
            val revokedKey = "revoked:$oldPubkey"
            if (revokedKey in clocks) return@updateGroup true
            val members = decodeList(entity.members, groupId, "members")
            if (oldPubkey !in members && (newPubkey.isEmpty() || newPubkey !in members)) {
                if (!allowAbsent) return@updateGroup false
                Log.i(TAG, "Revocation of ${oldPubkey.take(8)} in ${groupId.take(8)}: identity absent, tombstone only")
            }
            val replacement = newPubkey.takeIf { it.isNotEmpty() && "revoked:$it" !in clocks }
            val newMembers = when {
                replacement != null && replacement !in members -> members.map {
                    if (it ==
                        oldPubkey
                    ) {
                        replacement
                    } else {
                        it
                    }
                }
                else -> members - oldPubkey
            }
            val names = decodeMap(entity.memberNames, groupId, "memberNames").toMutableMap()
            val oldName = names.remove(oldPubkey)
            if (replacement != null && oldName != null && replacement !in names) names[replacement] = oldName
            val creator = if (entity.createdBy == oldPubkey) replacement.orEmpty() else entity.createdBy
            val newClocks = clocks.toMutableMap().apply {
                put(revokedKey, "$eventTimestamp:$eventId")
                // Lets a roster authored without knowledge of this revocation resolve to the successor.
                if (replacement != null) put("$REPLACED_PREFIX$oldPubkey", replacement)
            }
            val watermark = if (isNewerClock(
                    eventTimestamp,
                    eventId,
                    entity.lastMetaTimestamp to entity.lastMetaEventId
                )
            ) {
                eventTimestamp to eventId
            } else {
                entity.lastMetaTimestamp to entity.lastMetaEventId
            }
            groupDao.applyIdentityRevocation(
                groupId,
                json.encodeToString(stringListSerializer, newMembers),
                json.encodeToString(nameMapSerializer, sanitizeMemberNames(names, newMembers)),
                json.encodeToString(nameMapSerializer, newClocks),
                creator,
                watermark.first,
                watermark.second,
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

    private fun resolveRoster(members: List<String>, clocks: Map<String, String>): ResolvedRoster {
        val moved = LinkedHashMap<String, String>()
        val resolved = ArrayList<String>(members.size)
        for (member in members) {
            var current = member
            var hops = 0
            while ("revoked:$current" in clocks && hops++ < MAX_REPLACEMENT_HOPS) {
                current = clocks["$REPLACED_PREFIX$current"] ?: ""
                if (current.isEmpty()) break
            }
            if (current.isEmpty() || "revoked:$current" in clocks) {
                moved[member] = ""
                continue
            }
            if (current != member) moved[member] = current
            if (current !in resolved) resolved += current
        }
        return ResolvedRoster(resolved, moved)
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
        return Group(
            id = groupId,
            name = name,
            description = description,
            createdBy = createdBy,
            createdAt = createdAt,
            members = decodedMembers,
            relays = decodedRelays,
            memberNames = sanitizeMemberNames(decodedNames, decodedMembers),
            keyEpoch = keyEpoch
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

        /**
         * Highest epoch [deleteGroupKey] sweeps when the group row is gone and the real epoch is
         * unknown. Rotations are rare (one per member removal), so this comfortably covers any
         * realistic group's lifetime.
         */
        private const val MAX_ORPHAN_EPOCH_SWEEP = 64

        /** `memberClocks` entry recording which identity replaced a tombstoned one. */
        private const val REPLACED_PREFIX = "replaced:"

        /** A revoked identity replaced by another revoked identity is followed at most this far. */
        private const val MAX_REPLACEMENT_HOPS = 8
    }
}
