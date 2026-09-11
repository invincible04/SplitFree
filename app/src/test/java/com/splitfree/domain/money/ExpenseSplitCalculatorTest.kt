package com.splitfree.domain.money

import com.splitfree.domain.model.expense.SplitEntry
import com.splitfree.domain.model.expense.SplitType
import java.math.BigInteger
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpenseSplitCalculatorTest {
    private val calculator = ExpenseSplitCalculator(ExpenseInputParser(Locale.US))
    private val members = linkedSetOf("b", "a")

    @Test
    fun `exact 50 and 50 for 100 rupees yields 5000 paise each`() {
        val result = calculate(10000, SplitType.EXACT, mapOf("a" to "50", "b" to "50"))
        assertEquals(listOf(SplitEntry("a", 5000), SplitEntry("b", 5000)), result.splits)
        assertEquals(0L, result.remaining)
        assertNull(result.error)
    }

    @Test
    fun `exact decimal split preserves every minor unit`() {
        val result = calculate(10001, SplitType.EXACT, mapOf("a" to "50.01", "b" to "50"))
        assertEquals(listOf(SplitEntry("a", 5001), SplitEntry("b", 5000)), result.splits)
        assertNull(result.error)
    }

    @Test
    fun `exact preview exposes positive and negative remaining amounts`() {
        val short = calculate(10000, SplitType.EXACT, mapOf("a" to "30", "b" to "50"))
        val excess = calculate(10000, SplitType.EXACT, mapOf("a" to "70", "b" to "50"))
        assertEquals(2000L, short.remaining)
        assertEquals(-2000L, excess.remaining)
        assertNotNull(short.error)
        assertNotNull(excess.error)
    }

    @Test
    fun `JPY and KWD exact inputs match their currency`() {
        val yen = calculator.calculate(100, "JPY", SplitType.EXACT, members, mapOf("a" to "50", "b" to "50"))
        val dinar = calculator.calculate(100001, "KWD", SplitType.EXACT, members, mapOf("a" to "50.001", "b" to "50"))
        assertEquals(listOf(50L, 50L), yen.splits.map { it.share })
        assertEquals(listOf(50001L, 50000L), dinar.splits.map { it.share })
        assertNull(yen.error)
        assertNull(dinar.error)
    }

    @Test
    fun `equal remainder goes to sorted keys regardless of member input order`() {
        val result = calculate(101, SplitType.EQUAL)
        assertEquals(listOf(SplitEntry("a", 51), SplitEntry("b", 50)), result.splits)
        assertEquals(
            result,
            calculator.calculate(101, "INR", SplitType.EQUAL, members.toList().asReversed().toSet(), emptyMap())
        )
    }

    @Test
    fun `decimal percentages produce positive exact total`() {
        val result = calculate(10001, SplitType.PERCENTAGE, mapOf("a" to "12.5", "b" to "87.5"))
        assertEquals(listOf(SplitEntry("a", 1250), SplitEntry("b", 8751)), result.splits)
        assertEquals(10001L, result.splits.sumOf { it.share })
        assertNull(result.error)
    }

    @Test
    fun `decimal shares are proportional`() {
        val result = calculate(10000, SplitType.SHARES, mapOf("a" to "0.5", "b" to "1.5"))
        assertEquals(listOf(SplitEntry("a", 2500), SplitEntry("b", 7500)), result.splits)
        assertNull(result.error)
    }

    @Test
    fun `unselected input never changes allocation or percentage sum`() {
        val result = calculate(10000, SplitType.PERCENTAGE, mapOf("a" to "50", "b" to "50", "removed" to "99"))
        assertNull(result.error)
        assertEquals(2, result.splits.size)
    }

    @Test
    fun `large weights and amount cannot overflow multiplication or summation`() {
        val result = calculate(Long.MAX_VALUE, SplitType.SHARES, mapOf("a" to "9".repeat(32), "b" to "9".repeat(32)))
        assertNull(result.error)
        assertEquals(Long.MAX_VALUE, result.splits.sumOf { it.share })
        assertEquals(4611686018427387904L, result.splits[0].share)
    }

    @Test
    fun `single weighted allocation preserves exact Long maximum`() {
        val result = calculator.calculate(
            Long.MAX_VALUE,
            "INR",
            SplitType.SHARES,
            setOf("a"),
            mapOf("a" to "9".repeat(32))
        )
        assertNull(result.error)
        assertEquals(listOf(SplitEntry("a", Long.MAX_VALUE)), result.splits)
        assertEquals(0L, result.remaining)
    }

    @Test
    fun `exact aggregate overflow remains an invalid split not a wrapped valid sum`() {
        val maximum = "92233720368547758.07"
        val result = calculate(100, SplitType.EXACT, mapOf("a" to maximum, "b" to maximum))
        assertNotNull(result.error)
        assertNull(result.remaining)
    }

    @Test
    fun `zero allocations are not silently removed from selected participants`() {
        val equal = calculate(1, SplitType.EQUAL)
        val weighted = calculate(1, SplitType.PERCENTAGE, mapOf("a" to "50", "b" to "50"))
        assertNotNull(equal.error)
        assertNotNull(weighted.error)
        assertEquals(2, equal.splits.size)
        assertTrue(equal.splits.any { it.share == 0L })
    }

    @Test
    fun `zero negative missing and malformed member inputs block save`() {
        for (type in listOf(SplitType.EXACT, SplitType.PERCENTAGE, SplitType.SHARES)) {
            for (input in listOf("0", "-1", "", "1e2", "0.0000001")) {
                assertNotNull("$type $input", calculate(10000, type, mapOf("a" to input, "b" to "100")).error)
            }
            assertNotNull(calculate(10000, type, mapOf("a" to "100")).error)
        }
    }

    @Test
    fun `empty participants and invalid percentage total are rejected`() {
        assertNotNull(calculator.calculate(100, "INR", SplitType.EQUAL, emptySet(), emptyMap()).error)
        assertNotNull(calculate(100, SplitType.PERCENTAGE, mapOf("a" to "49.99", "b" to "50")).error)
    }

    @Test
    fun `valid allocations for many sizes conserve amount and order`() {
        for (size in 1..40) {
            val participants = (1..size).map { "member-$it" }.asReversed().toSet()
            val inputs = participants.associateWith { "1.5" }
            for (type in listOf(SplitType.EQUAL, SplitType.SHARES)) {
                val result = calculator.calculate(1000001, "INR", type, participants, inputs)
                assertNull(result.error)
                assertEquals(1000001L, result.splits.sumOf { it.share })
                assertEquals(participants.sorted(), result.splits.map { it.pubkey })
                assertTrue(result.splits.all { it.share > 0 })
            }
        }
    }

    @Test
    fun `partly filled exact inputs show remaining without making missing participant valid`() {
        val result = calculate(10000, SplitType.EXACT, mapOf("a" to "50"))
        assertEquals(5000L, result.remaining)
        assertNotNull(result.error)
    }

    @Test
    fun `percentage rounding awards fractional remainder not already exact quota`() {
        val result = calculator.calculate(
            6,
            "INR",
            SplitType.PERCENTAGE,
            linkedSetOf("c", "b", "a"),
            mapOf("a" to "50", "b" to "25", "c" to "25")
        )
        assertNull(result.error)
        assertEquals(listOf(SplitEntry("a", 3), SplitEntry("b", 2), SplitEntry("c", 1)), result.splits)
    }

    @Test
    fun `share rounding awards largest fractions with pubkey tie break`() {
        val inputs = mapOf("a" to "2", "b" to "1", "c" to "1")
        val result = calculator.calculate(6, "INR", SplitType.SHARES, setOf("c", "a", "b"), inputs)
        assertNull(result.error)
        assertEquals(listOf(SplitEntry("a", 3), SplitEntry("b", 2), SplitEntry("c", 1)), result.splits)
        assertEquals(result, calculator.calculate(6, "INR", SplitType.SHARES, setOf("b", "c", "a"), inputs))
    }

    @Test
    fun `differing weights conserve totals respect exact quota bounds and largest fractions`() {
        for (size in 2..12) {
            val members = (0 until size).map { "member-$it" }.sorted()
            val weights = members.mapIndexed { index, _ -> BigInteger.valueOf((index * index + 1).toLong()) }
            val total = weights.fold(BigInteger.ZERO, BigInteger::add)
            val inputs = members.mapIndexed { index, member ->
                member to weights[index].toString() + if (index % 2 == 0) ".0" else ""
            }.toMap()
            for (amount in listOf(size.toLong(), 101L, 10003L, Long.MAX_VALUE)) {
                val result = calculator.calculate(amount, "INR", SplitType.SHARES, members.asReversed().toSet(), inputs)
                assertEquals(amount, result.splits.sumOf { it.share })
                assertEquals(members, result.splits.map { it.pubkey })
                val quotas = weights.map { (BigInteger.valueOf(amount) * it).divideAndRemainder(total) }
                val roundedUp = result.splits.mapIndexed { index, split ->
                    val share = BigInteger.valueOf(split.share)
                    val floor = quotas[index][0]
                    val ceiling = floor + if (quotas[index][1].signum() > 0) BigInteger.ONE else BigInteger.ZERO
                    assertTrue("$amount $inputs $split", share >= floor && share <= ceiling)
                    share > floor
                }
                for (winner in members.indices.filter { roundedUp[it] }) {
                    for (other in members.indices.filter { !roundedUp[it] }) {
                        val comparison = quotas[winner][1].compareTo(quotas[other][1])
                        assertTrue(comparison > 0 || (comparison == 0 && members[winner] < members[other]))
                    }
                }
                assertEquals(result, calculator.calculate(amount, "INR", SplitType.SHARES, members.toSet(), inputs))
            }
        }
    }

    private fun calculate(
        amount: Long,
        type: SplitType,
        inputs: Map<String, String> = emptyMap()
    ): ExpenseSplitPreview = calculator.calculate(amount, "INR", type, members, inputs)
}
