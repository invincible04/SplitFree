package com.splitfree.domain.repository

import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupMeta
import kotlinx.serialization.Serializable

/** Durable desired name for one identity; revision identifies the request across publication retries. */
@Serializable
data class DisplayNameIntent(val identityPubkey: String, val revision: String, val name: String, val requestedAt: Long)

/** Signed rename bound to the group snapshot that must still match at commit time. */
data class PreparedDisplayName(val intent: DisplayNameIntent, val group: Group, val event: NostrEvent)

internal fun DisplayNameIntent.groupMeta(group: Group): GroupMeta = GroupMeta(
    name = group.name,
    description = group.description,
    createdBy = group.createdBy,
    createdAt = group.createdAt,
    members = group.members,
    relays = group.relays,
    memberNames = group.memberNames + (identityPubkey to name),
    keyEpoch = group.keyEpoch,
    originalCreator = group.originalCreator.takeIf {
        com.splitfree.domain.model.group.GroupIdentity.matches(group.id, it, group.createdAt)
    }.orEmpty(),
    creatorTransitions = group.creatorTransitions
)

/** Local durable queueing only; does not certify relay acceptance. */
enum class DisplayNameDelivery { DURABLY_QUEUED }

data class DisplayNamePublishResult(
    val groups: Map<String, DisplayNameDelivery> = emptyMap(),
    val deferredGroups: Set<String> = emptySet(),
    val superseded: Boolean = false
) {
    val needsRetry: Boolean get() = deferredGroups.isNotEmpty()
}

/** Durable rename preparation and per-identity/group publication state. */
interface DisplayNameRepositoryContract {
    suspend fun saveIntent(intent: DisplayNameIntent)
    suspend fun getPrepared(intent: DisplayNameIntent, groupId: String): PreparedDisplayName?
    suspend fun committedEventId(intent: DisplayNameIntent, groupId: String): String?

    /**
     * Reserves a timestamp above [floor] and prior reservations, within one hour of [now].
     * Both arguments are epoch seconds; an unsafe clock raises [DisplayNameClockDeferredException].
     */
    suspend fun reserveTimestamp(intent: DisplayNameIntent, groupId: String, floor: Long, now: Long): Long

    suspend fun savePrepared(prepared: PreparedDisplayName)
    suspend fun discardPrepared(intent: DisplayNameIntent, groupId: String)
}

class DisplayNameClockDeferredException : IllegalStateException("Display name clock is ahead of the allowed window")
class DisplayNameGroupChangedException : IllegalStateException("Group changed while publishing display name")
