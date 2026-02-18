package com.splitfree.domain.usecase.expense

import com.splitfree.domain.model.expense.SplitEntry
import com.splitfree.domain.model.expense.SplitType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the split computation logic extracted from AddExpenseViewModel.
 * Design doc Section 11.3.
 */
class SplitComputationTest {
    // Mirror the private computeSplits logic for testing
    private fun computeSplits(
        amount: Long,
        type: SplitType,
        members: List<String>,
        inputs: Map<String, Long>
    ): List<SplitEntry> = when (type) {
        SplitType.EQUAL -> {
            val perPerson = amount / members.size
            val remainder = (amount % members.size).toInt()
            members.mapIndexed { i, pk -> SplitEntry(pk, perPerson + if (i < remainder) 1 else 0) }
        }

        SplitType.EXACT -> {
            members.map { pk -> SplitEntry(pk, inputs[pk] ?: 0L) }
        }

        SplitType.PERCENTAGE -> {
            val totalPct = inputs.values.sum()
            require(totalPct == 100L) { "Percentages must sum to 100" }
            var allocated = 0L
            members.mapIndexed { i, pk ->
                val share =
                    if (i == members.lastIndex) {
                        amount - allocated
                    } else {
                        amount * (inputs[pk] ?: 0L) / 100
                    }
                allocated += share
                SplitEntry(pk, share)
            }
        }

        SplitType.SHARES -> {
            val totalUnits = inputs.values.sum()
            require(totalUnits > 0) { "Total shares must be positive" }
            var allocated = 0L
            members.mapIndexed { i, pk ->
                val share =
                    if (i == members.lastIndex) {
                        amount - allocated
                    } else {
                        amount * (inputs[pk] ?: 0L) / totalUnits
                    }
                allocated += share
                SplitEntry(pk, share)
            }
        }
    }

    private val alice = "alice"
    private val bob = "bob"
    private val charlie = "charlie"
    private val members3 = listOf(alice, bob, charlie)

    // --- EQUAL splits ---

    @Test
    fun `equal split divides evenly`() {
        val splits = computeSplits(300, SplitType.EQUAL, members3, emptyMap())
        assertEquals(3, splits.size)
        assertTrue(splits.all { it.share == 100L })
        assertEquals(300L, splits.sumOf { it.share })
    }

    @Test
    fun `equal split distributes remainder correctly`() {
        // 100 / 3 = 33 remainder 1 → first person gets 34
        val splits = computeSplits(100, SplitType.EQUAL, members3, emptyMap())
        assertEquals(34L, splits[0].share) // alice gets remainder
        assertEquals(33L, splits[1].share)
        assertEquals(33L, splits[2].share)
        assertEquals(100L, splits.sumOf { it.share })
    }

    @Test
    fun `equal split remainder 2 of 3`() {
        // 101 / 3 = 33 remainder 2 → first two get 34
        val splits = computeSplits(101, SplitType.EQUAL, members3, emptyMap())
        assertEquals(34L, splits[0].share)
        assertEquals(34L, splits[1].share)
        assertEquals(33L, splits[2].share)
        assertEquals(101L, splits.sumOf { it.share })
    }

    @Test
    fun `equal split 1 paise among 3 people`() {
        val splits = computeSplits(1, SplitType.EQUAL, members3, emptyMap())
        assertEquals(1L, splits[0].share)
        assertEquals(0L, splits[1].share)
        assertEquals(0L, splits[2].share)
        assertEquals(1L, splits.sumOf { it.share })
    }

    @Test
    fun `equal split between 2 people`() {
        val splits = computeSplits(100, SplitType.EQUAL, listOf(alice, bob), emptyMap())
        assertEquals(50L, splits[0].share)
        assertEquals(50L, splits[1].share)
    }

    @Test
    fun `equal split single person`() {
        val splits = computeSplits(500, SplitType.EQUAL, listOf(alice), emptyMap())
        assertEquals(1, splits.size)
        assertEquals(500L, splits[0].share)
    }

    // --- EXACT splits ---

    @Test
    fun `exact split uses provided amounts`() {
        val inputs = mapOf(alice to 200L, bob to 150L, charlie to 150L)
        val splits = computeSplits(500, SplitType.EXACT, members3, inputs)
        assertEquals(200L, splits.find { it.pubkey == alice }!!.share)
        assertEquals(150L, splits.find { it.pubkey == bob }!!.share)
        assertEquals(150L, splits.find { it.pubkey == charlie }!!.share)
    }

    @Test
    fun `exact split missing member defaults to 0`() {
        val inputs = mapOf(alice to 100L) // bob and charlie missing
        val splits = computeSplits(100, SplitType.EXACT, members3, inputs)
        assertEquals(100L, splits.find { it.pubkey == alice }!!.share)
        assertEquals(0L, splits.find { it.pubkey == bob }!!.share)
        assertEquals(0L, splits.find { it.pubkey == charlie }!!.share)
    }

    // --- PERCENTAGE splits ---

    @Test
    fun `percentage split basic`() {
        val inputs = mapOf(alice to 50L, bob to 30L, charlie to 20L)
        val splits = computeSplits(1000, SplitType.PERCENTAGE, members3, inputs)
        assertEquals(500L, splits.find { it.pubkey == alice }!!.share)
        assertEquals(300L, splits.find { it.pubkey == bob }!!.share)
        assertEquals(200L, splits.find { it.pubkey == charlie }!!.share)
        assertEquals(1000L, splits.sumOf { it.share })
    }

    @Test
    fun `percentage split handles rounding remainder`() {
        // 33% + 33% + 34% of 100 = 33 + 33 + 34 = 100
        val inputs = mapOf(alice to 33L, bob to 33L, charlie to 34L)
        val splits = computeSplits(100, SplitType.PERCENTAGE, members3, inputs)
        assertEquals(100L, splits.sumOf { it.share })
    }

    @Test
    fun `percentage split 33-33-34 of 1000`() {
        val inputs = mapOf(alice to 33L, bob to 33L, charlie to 34L)
        val splits = computeSplits(1000, SplitType.PERCENTAGE, members3, inputs)
        // alice: 1000*33/100 = 330, bob: 1000*33/100 = 330, charlie: 1000-660 = 340
        assertEquals(330L, splits[0].share)
        assertEquals(330L, splits[1].share)
        assertEquals(340L, splits[2].share)
        assertEquals(1000L, splits.sumOf { it.share })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `percentage split rejects non-100 total`() {
        val inputs = mapOf(alice to 50L, bob to 30L, charlie to 10L) // = 90
        computeSplits(1000, SplitType.PERCENTAGE, members3, inputs)
    }

    // --- SHARES splits ---

    @Test
    fun `shares split 2-1-1 ratio`() {
        val inputs = mapOf(alice to 2L, bob to 1L, charlie to 1L)
        val splits = computeSplits(400, SplitType.SHARES, members3, inputs)
        assertEquals(200L, splits.find { it.pubkey == alice }!!.share)
        assertEquals(100L, splits.find { it.pubkey == bob }!!.share)
        assertEquals(100L, splits.find { it.pubkey == charlie }!!.share)
        assertEquals(400L, splits.sumOf { it.share })
    }

    @Test
    fun `shares split handles remainder`() {
        // 100 split 1:1:1 = 33+33+34
        val inputs = mapOf(alice to 1L, bob to 1L, charlie to 1L)
        val splits = computeSplits(100, SplitType.SHARES, members3, inputs)
        assertEquals(100L, splits.sumOf { it.share })
        // Last person absorbs remainder
        assertEquals(34L, splits[2].share)
    }

    @Test
    fun `shares split 3-2-1 of 600`() {
        val inputs = mapOf(alice to 3L, bob to 2L, charlie to 1L)
        val splits = computeSplits(600, SplitType.SHARES, members3, inputs)
        assertEquals(300L, splits[0].share) // 600*3/6
        assertEquals(200L, splits[1].share) // 600*2/6
        assertEquals(100L, splits[2].share) // 600-500
        assertEquals(600L, splits.sumOf { it.share })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `shares split rejects zero total units`() {
        val inputs = mapOf(alice to 0L, bob to 0L, charlie to 0L)
        computeSplits(100, SplitType.SHARES, members3, inputs)
    }

    // --- Invariant: sum always equals amount ---

    @Test
    fun `all split types preserve total amount for odd numbers`() {
        val amount = 997L // prime number, hard to divide
        val members = listOf("a", "b", "c", "d", "e", "f", "g") // 7 people

        // Equal
        val equal = computeSplits(amount, SplitType.EQUAL, members, emptyMap())
        assertEquals(amount, equal.sumOf { it.share })

        // Percentage (irregular)
        val pctInputs = mapOf("a" to 15L, "b" to 15L, "c" to 14L, "d" to 14L, "e" to 14L, "f" to 14L, "g" to 14L)
        val pct = computeSplits(amount, SplitType.PERCENTAGE, members, pctInputs)
        assertEquals(amount, pct.sumOf { it.share })

        // Shares (irregular)
        val shareInputs = mapOf("a" to 3L, "b" to 2L, "c" to 2L, "d" to 1L, "e" to 1L, "f" to 1L, "g" to 1L)
        val shares = computeSplits(amount, SplitType.SHARES, members, shareInputs)
        assertEquals(amount, shares.sumOf { it.share })
    }
}
