package com.splitfree.data.repository

import com.splitfree.data.local.dao.GroupDao
import com.splitfree.data.local.entities.GroupEntity
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
     * Metadata writes are read-modify-write (roster scope, per-member name clocks), so writers to the
     * same group are serialised here. Relay and nearby ingestion may otherwise interleave.
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
     * The un-epoched legacy key (stored under the plain `groupId`) is only a valid
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
     * Remove every key stored for [groupId]: the un-epoched legacy entry plus `"$groupId:$epoch"`
     * for each epoch up to the group's current one. If the Room row is already gone the epoch is
     * unknown, so epochs `0..MAX_ORPHAN_EPOCH_SWEEP` are swept instead.
     *
     * No caller yet. A "leave group" flow must call it so
     * the device stops being able to decrypt a group it no longer belongs to.
     */
    override suspend fun deleteGroupKey(groupId: String) {
        val maxEpoch = groupDao.getById(groupId)?.keyEpoch ?: MAX_ORPHAN_EPOCH_SWEEP
        keyStore.remove(groupId)
        for (epoch in 0..maxEpoch) keyStore.remove("$groupId:$epoch")
    }

    suspend fun getGroupEntity(groupId: String): GroupEntity? = groupDao.getById(groupId)

    override suspend fun save(group: Group, groupKey: String) {
        // Persist the key FIRST. SecureStorage.putString commits synchronously and throws
        // SecureStorageException on failure, so if the key cannot be stored we never reach
        // groupDao.insert and no Room row exists without a recoverable key behind it.
        keyStore.putString("${group.id}:${group.keyEpoch}", groupKey)
        // Also store under plain groupId for backward compat at epoch 0
        if (group.keyEpoch == 0) keyStore.putString(group.id, groupKey)
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
        val existing = keyStore.getString("$groupId:$epoch", null)
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
        groupDao.updateCreator(groupId, createdBy, createdAt)
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
        applyRoster: Boolean
    ): Boolean {
        require(eventTimestamp > 0) { "group_meta needs the event's created_at to be ordered" }
        if (members.size > RelayDefaults.MAX_GROUP_MEMBERS) {
            Log.w(
                TAG,
                "Rejecting group_meta with ${members.size} members (max ${RelayDefaults.MAX_GROUP_MEMBERS})"
            )
            return false
        }
        return lockFor(groupId).withLock {
            val entity = groupDao.getById(groupId) ?: return@withLock false
            if (!isNewerClock(eventTimestamp, eventId, entity.lastMetaTimestamp to entity.lastMetaEventId)) {
                return@withLock false
            }
            val storedMembers = decodeList(entity.members, groupId, "members")
            val finalMembers = if (applyRoster) members else storedMembers
            if (!applyRoster) {
                Log.i(TAG, "group_meta ${eventId.take(8)} predates the current key epoch; roster kept")
            }
            val storedNames = decodeMap(entity.memberNames, groupId, "memberNames")
            val clocks = decodeMap(entity.memberClocks, groupId, "memberClocks")
            // Per member: the creator's map wins unless that member's own rename is newer.
            val merged = LinkedHashMap<String, String>()
            for (member in finalMembers) {
                val ownClock = clocks[member]?.let(::parseMemberClock)
                val memberIsNewer =
                    ownClock != null && isNewerClock(ownClock.first, ownClock.second, eventTimestamp to eventId)
                val chosen = if (memberIsNewer) storedNames[member] else memberNames[member]
                if (chosen != null) merged[member] = chosen
            }
            val safeRelays = relays.filter { it.startsWith("wss://") && it.length <= 256 }
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
                eventId
            ) == 1
        }
    }

    override suspend fun applyKeyRotation(
        groupId: String,
        epoch: Int,
        members: List<String>,
        memberNames: Map<String, String>
    ): Boolean {
        if (members.size > RelayDefaults.MAX_GROUP_MEMBERS) {
            Log.w(TAG, "Rejecting key_rotation with ${members.size} members (max ${RelayDefaults.MAX_GROUP_MEMBERS})")
            return false
        }
        return lockFor(groupId).withLock {
            groupDao.applyKeyRotation(
                groupId,
                epoch,
                json.encodeToString(stringListSerializer, members),
                json.encodeToString(nameMapSerializer, sanitizeMemberNames(memberNames, members))
            ) == 1
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
        lockFor(groupId).withLock {
            groupDao.overrideMembership(
                groupId,
                json.encodeToString(stringListSerializer, members),
                json.encodeToString(nameMapSerializer, sanitizeMemberNames(memberNames, members)),
                createdBy,
                eventTimestamp,
                eventId
            )
        }
    }

    /**
     * Per-member self-update, ordered by the member's own `(eventTimestamp, eventId)` clock stored
     * in `memberClocks` rather than by the creator's `lastMetaTimestamp` watermark, so a member
     * renaming themselves can neither block nor be blocked by the creator's metas.
     *
     * Only the author's own entries are touched: their membership (when [join]) and their own
     * display name. Every other member's name is carried over untouched.
     *
     * Read-modify-write; callers are expected to serialise event ingestion per group.
     */
    override suspend fun applyMemberSelfUpdate(
        groupId: String,
        author: String,
        eventTimestamp: Long,
        eventId: String,
        join: Boolean,
        displayName: String?
    ): Boolean = lockFor(groupId).withLock {
        val entity = groupDao.getById(groupId) ?: return@withLock false
        val clocks = decodeMap(entity.memberClocks, groupId, "memberClocks")
        val stored = clocks[author]?.let(::parseMemberClock)
        if (stored != null && !isNewerClock(eventTimestamp, eventId, stored)) return@withLock false

        val members = decodeList(entity.members, groupId, "members")
        val newMembers = when {
            join && author !in members -> members + author
            else -> members
        }
        if (author !in newMembers) return@withLock false // name change for a non-member: nothing to apply
        if (newMembers.size > RelayDefaults.MAX_GROUP_MEMBERS) {
            Log.w(
                TAG,
                "Rejecting self-join to $groupId: ${newMembers.size} members (max ${RelayDefaults.MAX_GROUP_MEMBERS})"
            )
            return@withLock false
        }

        val names = decodeMap(entity.memberNames, groupId, "memberNames").toMutableMap()
        if (displayName != null) {
            val safeName = displayName.trim().take(MAX_DISPLAY_NAME_LENGTH)
            if (safeName.isEmpty()) names.remove(author) else names[author] = safeName
        }
        val newClocks = clocks.toMutableMap().apply { put(author, "$eventTimestamp:$eventId") }

        groupDao.updateMemberSelf(
            groupId,
            json.encodeToString(stringListSerializer, newMembers),
            json.encodeToString(nameMapSerializer, names),
            json.encodeToString(nameMapSerializer, newClocks)
        )
        true
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
    }
}
