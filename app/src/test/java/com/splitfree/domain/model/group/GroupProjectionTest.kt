package com.splitfree.domain.model.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GroupProjectionTest {
    private val initial = Group(
        "g",
        "Trip",
        createdBy = "A",
        createdAt = 1,
        members = listOf("A", "M"),
        relays = emptyList()
    )
    private fun revoke(old: String, new: String, time: Long) = GroupControlFact(
        "$old-$new-$time",
        "revocation",
        time,
        0,
        author = old,
        successor = new,
        proven = true
    )
    private fun meta(time: Long, members: List<String>) = GroupControlFact(
        "meta-$time",
        "meta",
        time,
        0,
        author = "A",
        meta = GroupMeta(name = "Trip", createdBy = "A", members = members)
    )
    private fun <T> permutations(values: List<T>): List<List<T>> = if (values.isEmpty()) {
        listOf(emptyList())
    } else {
        values.indices.flatMap { index ->
            permutations(values.filterIndexed { i, _ -> i != index }).map {
                listOf(values[index]) +
                    it
            }
        }
    }

    @Test
    fun `every arrival permutation gives the same canonical creator and roster`() {
        val facts =
            listOf(meta(10, listOf("A", "M")), meta(20, listOf("A", "N")), revoke("A", "B", 40), revoke("A", "C", 30))
        val expected = GroupProjectionReducer.reduce(GroupProjection(initial, facts = facts))
        assertEquals("C", expected.group.createdBy)
        assertEquals(listOf("C", "N"), expected.group.members)
        for (order in permutations(facts)) {
            var state = GroupProjection(initial)
            for (fact in order) {
                state = state.copy(facts = state.facts + fact)
                GroupProjectionReducer.reduce(state)
            }
            assertEquals(expected, GroupProjectionReducer.reduce(state))
        }
    }

    @Test
    fun `earlier winner never reintroduces a seat removed by newer metadata`() {
        val facts = listOf(revoke("M", "B", 20), meta(30, listOf("A")), revoke("M", "C", 10))
        for (order in permutations(facts)) {
            assertEquals(
                listOf("A"),
                GroupProjectionReducer.reduce(GroupProjection(initial, facts = order)).group.members
            )
        }
    }

    @Test
    fun `epoch boundary wins over old history even with later event timestamps`() {
        val facts = listOf(
            GroupControlFact(
                "rotation:1",
                "rotation",
                0,
                1,
                meta = GroupMeta(members = listOf("A"))
            ),
            meta(999, listOf("A", "M")),
            revoke("M", "B", 30)
        )
        for (order in permutations(facts)) {
            val result = GroupProjectionReducer.reduce(GroupProjection(initial, facts = order))
            assertEquals(1, result.group.keyEpoch)
            assertEquals(listOf("A"), result.group.members)
        }
    }

    @Test
    fun `cycle has no live member and cannot erase the original financial identity`() {
        val result = GroupProjectionReducer.reduce(
            GroupProjection(
                initial.copy(members = listOf("A")),
                facts = listOf(revoke("A", "B", 10), revoke("B", "A", 20))
            )
        )
        assertEquals(emptyList<String>(), result.group.members)
        assertEquals("", result.group.createdBy)
        assertEquals(null, GroupProjectionReducer.resolve("A", result.clocks, "succeeded:", false))
    }

    @Test
    fun `unproven replacement never creates a balance link`() {
        val result = GroupProjectionReducer.reduce(
            GroupProjection(
                initial,
                facts = listOf(revoke("M", "B", 10).copy(proven = false))
            )
        )
        assertEquals(listOf("A", "B"), result.group.members)
        assertFalse("succeeded:M" in result.clocks)
    }

    @Test
    fun `canonical creator does not take independent losing successor membership`() {
        val seed = initial.copy(members = listOf("A", "B"))
        val result = GroupProjectionReducer.reduce(
            GroupProjection(
                seed,
                facts = listOf(revoke("A", "B", 20), revoke("A", "C", 10))
            )
        )
        assertEquals(listOf("C", "B"), result.group.members)
        assertEquals("C", result.group.createdBy)
    }

    @Test
    fun `legacy incomplete checkpoint retains epoch and retirement without full history`() {
        val result = GroupProjectionReducer.reduce(
            GroupProjection(
                initial.copy(keyEpoch = 2, members = listOf("A")),
                checkpointClocks = mapOf("revoked:M" to "30:r"),
                incomplete = true,
                facts = listOf(meta(10, listOf("A", "M")))
            )
        )
        assertEquals(2, result.group.keyEpoch)
        assertEquals(listOf("A"), result.group.members)
        assertEquals("30:r", result.clocks["revoked:M"])
    }

    @Test
    fun `stale same epoch metadata cannot resurrect a seat from an incomplete checkpoint`() {
        val state = GroupProjection(
            initial.copy(members = listOf("A")),
            checkpointTimestamp = 100,
            checkpointEventId = "newer",
            incomplete = true,
            facts = listOf(meta(10, listOf("A", "M")))
        )
        assertEquals(listOf("A"), GroupProjectionReducer.reduce(state).group.members)
    }

    @Test
    fun `root identity binding never grants post retirement metadata authority`() {
        val author = "a".repeat(64)
        val group = initial.copy(id = GroupIdentity.derive(author, 1), createdBy = "", members = listOf(author, "M"))
        val retired = revoke(author, "B", 10)
        val late = GroupControlFact(
            "late",
            "meta",
            20,
            0,
            author = author,
            meta = GroupMeta("Untrusted", createdBy = author, createdAt = 1, members = listOf(author, "X"))
        )
        val root = late.copy(
            id = "root",
            timestamp = 1,
            meta = late.meta!!.copy(name = "Trip", members = listOf(author, "M"))
        )
        for (facts in listOf(listOf(retired, late), listOf(root, retired, late))) {
            val result = GroupProjectionReducer.reduce(GroupProjection(group, facts = facts))
            assertEquals("B", result.group.createdBy)
            assertEquals(listOf("B", "M"), result.group.members)
            assertEquals("Trip", result.group.name)
        }
    }

    @Test
    fun `an independently seated successor keeps its original position`() {
        val result = GroupProjectionReducer.reduce(
            GroupProjection(
                initial.copy(members = listOf("A", "M", "N", "B")),
                facts = listOf(revoke("M", "B", 10))
            )
        )
        assertEquals(listOf("A", "N", "B"), result.group.members)
    }

    @Test
    fun `raw signed rotation recipients are not retargeted from losing to winning successor`() {
        val rotation = GroupControlFact(
            "rotate",
            "rotation",
            40,
            1,
            author = "A",
            rotation = KeyRotation(1, mapOf("A" to "encryptedA", "B" to "encryptedB"), listOf("A", "B"), "N")
        )
        val facts = listOf(revoke("M", "B", 30), revoke("M", "C", 20), rotation)
        for (order in permutations(facts)) {
            val result = GroupProjectionReducer.reduce(GroupProjection(initial, facts = order))
            assertEquals(listOf("A", "B"), result.group.members)
            assertEquals("C", result.clocks["succeeded:M"])
            assertEquals(setOf("A", "B"), rotation.rotation!!.encryptedKeys.keys)
        }
    }

    @Test
    fun `new epoch roster uses its own clock despite older epoch timestamp skew`() {
        val old = meta(900, listOf("A", "M"))
        val boundary = GroupControlFact(
            "rotation",
            "rotation",
            5,
            1,
            rotation = KeyRotation(1, emptyMap(), listOf("A"), "M")
        )
        val newer = meta(10, listOf("A", "N")).copy(id = "epoch1", epoch = 1)
        for (order in permutations(listOf(old, boundary, newer))) {
            val result = GroupProjectionReducer.reduce(GroupProjection(initial, facts = order))
            assertEquals(listOf("A", "N"), result.group.members)
            assertEquals(1, result.group.keyEpoch)
        }
    }
}
