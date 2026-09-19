package com.splitfree.domain.model.group

import kotlinx.serialization.Serializable

/** Durable group checkpoint and admitted control facts; excludes secret keys and ledger plaintext. */
@Serializable
data class GroupProjection(
    val checkpoint: Group,
    val checkpointClocks: Map<String, String> = emptyMap(),
    val checkpointTimestamp: Long = 0,
    val checkpointEventId: String = "",
    val incomplete: Boolean = false,
    val facts: List<GroupControlFact> = emptyList(),
    val rootCreator: String = "",
    val rootCreatedAt: Long = 0,
    val creatorTransitions: List<CreatorTransition> = emptyList()
) {
    fun verifiedRoot(): Pair<String, Long>? {
        fun bound(creator: String, createdAt: Long): Pair<String, Long>? =
            (creator to createdAt).takeIf { GroupIdentity.matches(checkpoint.id, creator, createdAt) }
        bound(rootCreator, rootCreatedAt)?.let { return it }
        bound(checkpoint.originalCreator, checkpoint.createdAt)?.let { return it }
        bound(checkpoint.createdBy, checkpoint.createdAt)?.let { return it }
        return facts.asSequence().filter { it.kind == "meta" }
            .sortedWith(compareBy({ it.timestamp }, { it.id }))
            .mapNotNull { fact ->
                fact.meta?.takeIf { it.createdBy == fact.author }?.let { bound(fact.author, it.createdAt) }
            }.firstOrNull()
    }

    fun verifiedCreatorTransitions(): List<CreatorTransition> {
        val root = verifiedRoot()?.first ?: return emptyList()
        val proofs = creatorTransitions.filter { it.fact() in facts && it.verify(checkpoint.id) }
        val reachable = mutableSetOf(root)
        repeat(proofs.size) {
            proofs.filter { it.oldPubkey in reachable }.forEach { reachable += it.newPubkey }
        }
        return proofs.filter { it.oldPubkey in reachable }.sortedWith(compareBy({ it.timestamp }, { it.eventId }))
    }
}

/** Local control input from authenticated ingestion or trusted repository calls; not a signed envelope. */
@Serializable
data class GroupControlFact(
    val id: String,
    val kind: String,
    val timestamp: Long,
    val epoch: Int,
    val author: String = "",
    val meta: GroupMeta? = null,
    val successor: String = "",
    val proven: Boolean = false,
    val join: Boolean = false,
    val displayName: String? = null,
    val roster: Boolean = true,
    val trusted: Boolean = false,
    val rotation: KeyRotation? = null
)

/**
 * Rebuilds group state deterministically from a checkpoint and admitted facts, without I/O.
 * Within each epoch, rotations and checkpoints precede other facts, then timestamp and ID break ties.
 */
object GroupProjectionReducer {
    data class Result(val group: Group, val clocks: Map<String, String>, val timestamp: Long, val eventId: String)

    fun reduce(state: GroupProjection): Result {
        val seed = state.checkpoint
        val ordered = state.facts.sortedWith(
            compareBy<GroupControlFact>({ it.epoch }, {
                if (it.kind == "rotation" ||
                    it.kind == "checkpoint"
                ) {
                    0
                } else {
                    1
                }
            }, { it.timestamp }, { it.id })
        )
        val revocations = state.facts.filter { it.kind == "revocation" }
            .groupBy { it.author }.mapValues { (_, facts) -> facts.minWith(compareBy({ it.timestamp }, { it.id })) }
        val root = state.verifiedRoot()
        var group = if (root != null) {
            seed.copy(createdBy = root.first, originalCreator = root.first, createdAt = root.second)
        } else {
            seed
        }
        val clocks = state.checkpointClocks.filterKeys { !it.startsWith("seated:") }.toMutableMap()
        var watermark = state.checkpointTimestamp to state.checkpointEventId
        var metadataClock = state.checkpointTimestamp to state.checkpointEventId
        var epoch = seed.keyEpoch
        var rosterClock = state.checkpointTimestamp to state.checkpointEventId
        var rosterEpoch = seed.keyEpoch
        var members = group.members
        var names = group.memberNames
        fun resolve(key: String): String? = resolve(key, clocks, "replaced:", true)
        // Binding the original creator is not permission for a retired key to write new metadata.
        group = group.copy(createdBy = group.createdBy.takeIf { it.isNotEmpty() }?.let(::resolve).orEmpty())
        fun roster(values: List<String>): List<String> {
            val independent = values.filter { resolve(it) == it }.toSet()
            return values.mapNotNull { key -> resolve(key)?.takeUnless { it != key && it in independent } }.distinct()
        }
        fun remap(values: Map<String, String>): Map<String, String> {
            val mapped = linkedMapOf<String, String>()
            values.filterKeys { resolve(it) == it }.forEach { (key, name) -> mapped[key] = name }
            values.forEach { (key, name) -> resolve(key)?.let { mapped.putIfAbsent(it, name) } }
            return mapped
        }
        for (fact in ordered) {
            val clock = fact.timestamp to fact.id
            when (fact.kind) {
                "rotation", "checkpoint" -> {
                    if (fact.epoch < epoch) continue
                    epoch = fact.epoch
                    rosterEpoch = fact.epoch
                    rosterClock = 0L to ""
                    members = roster(fact.rotation?.members ?: checkNotNull(fact.meta).members)
                    names = remap(fact.meta?.memberNames ?: names).filterKeys { it in members }
                }
                "revocation" -> {
                    if (revocations[fact.author] != fact) continue
                    val prior = clocks["revoked:${fact.author}"]?.let(::parseClock)
                    if (prior != null && compare(clock, prior) > 0) continue
                    clocks["revoked:${fact.author}"] = "${fact.timestamp}:${fact.id}"
                    clocks.remove("replaced:${fact.author}")
                    clocks.remove("succeeded:${fact.author}")
                    if (fact.successor.isNotEmpty()) {
                        clocks["replaced:${fact.author}"] = fact.successor
                        if (fact.proven) clocks["succeeded:${fact.author}"] = fact.successor
                    }
                    members = roster(members)
                    names = remap(names).filterKeys { it in members }
                    if (group.createdBy == fact.author) group = group.copy(createdBy = resolve(fact.author).orEmpty())
                    if (compare(clock, watermark) > 0) watermark = clock
                }
                "meta" -> {
                    val meta = checkNotNull(fact.meta)
                    val creator = fact.trusted || fact.author == group.createdBy
                    if (creator) {
                        // The revocation watermark must not suppress older metadata during replay.
                        // Roster and metadata clocks are evaluated separately in canonical order.
                        if (fact.roster &&
                            fact.epoch >= epoch &&
                            (fact.epoch > rosterEpoch || compare(clock, rosterClock) > 0)
                        ) {
                            epoch = fact.epoch
                            rosterEpoch = fact.epoch
                            rosterClock = clock
                            members = roster(meta.members)
                        }
                        if (compare(clock, metadataClock) <= 0) continue
                        metadataClock = clock
                        val incoming = remap(meta.memberNames)
                        names = members.mapNotNull { member ->
                            val own = clocks[member]?.let(::parseClock)
                            val value = if (own != null && compare(own, clock) > 0) names[member] else incoming[member]
                            value?.takeIf { it.isNotBlank() }?.let { member to it.take(50).trim() }
                        }.toMap()
                        val creatorKey = if (fact.trusted) {
                            meta.createdBy.ifEmpty {
                                group.createdBy
                            }
                        } else {
                            group.createdBy
                        }
                        group = group.copy(
                            name = meta.name,
                            description = meta.description,
                            relays = meta.relays,
                            createdBy = resolve(creatorKey).orEmpty()
                        )
                        if (compare(clock, watermark) > 0) watermark = clock
                    } else {
                        if ("revoked:${fact.author}" in clocks) continue
                        if (fact.author !in members &&
                            fact.epoch >= epoch &&
                            fact.author in meta.members &&
                            (meta.members.toSet() - members.toSet() - fact.author).isEmpty()
                        ) {
                            members =
                                members + fact.author
                        }
                        if (fact.author in members && fact.author in meta.memberNames) {
                            val prior = clocks[fact.author]?.let(::parseClock)
                            if (prior == null || compare(clock, prior) > 0) {
                                clocks[fact.author] = "${fact.timestamp}:${fact.id}"
                                if (compare(clock, metadataClock) > 0) {
                                    names =
                                        names + (fact.author to meta.memberNames.getValue(fact.author).take(50).trim())
                                }
                            }
                        }
                    }
                }
                "self" -> {
                    if ("revoked:${fact.author}" in clocks) continue
                    if (fact.join && fact.epoch >= epoch && fact.author !in members) members = members + fact.author
                    if (fact.author !in members) continue
                    val prior = clocks[fact.author]?.let(::parseClock)
                    if (fact.displayName != null && (prior == null || compare(clock, prior) > 0)) {
                        clocks[fact.author] = "${fact.timestamp}:${fact.id}"
                        if (compare(clock, metadataClock) >
                            0
                        ) {
                            names = names + (fact.author to fact.displayName.take(50).trim())
                        }
                    }
                    if (fact.join) clocks["join:${fact.author}"] = "${fact.timestamp}:${fact.id}"
                }
            }
        }
        members = roster(members)
        names = remap(names).filter { it.key in members && it.value.isNotBlank() }
        val currentCreator = group.createdBy.takeIf { it.isNotEmpty() }?.let(::resolve).orEmpty()
        return Result(
            group.copy(
                members = members,
                memberNames = names,
                keyEpoch = maxOf(epoch, seed.keyEpoch),
                createdBy = currentCreator,
                creatorTransitions = state.verifiedCreatorTransitions()
            ),
            clocks,
            watermark.first,
            watermark.second
        )
    }

    fun parseClock(value: String): Pair<Long, String>? {
        val split = value.indexOf(':')
        if (split < 0) return null
        return (value.substring(0, split).toLongOrNull() ?: return null) to value.substring(split + 1)
    }

    private fun compare(a: Pair<Long, String>, b: Pair<Long, String>): Int =
        a.first.compareTo(b.first).takeIf { it != 0 } ?: a.second.compareTo(b.second)

    fun resolve(start: String, clocks: Map<String, String>, prefix: String, requireLive: Boolean): String? {
        var key = start
        val seen = hashSetOf<String>()
        while ("revoked:$key" in clocks) {
            if (!seen.add(key) || seen.size > 4096) return null
            val next = clocks["$prefix$key"].orEmpty()
            if (next.isEmpty()) return if (requireLive) null else key
            key = next
        }
        return key
    }
}
