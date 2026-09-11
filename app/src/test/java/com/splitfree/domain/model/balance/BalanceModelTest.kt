package com.splitfree.domain.model.balance

import com.splitfree.domain.model.expense.DebtTransaction
import com.splitfree.domain.model.expense.Settlement
import com.splitfree.domain.model.group.Group
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BalanceModelTest {
    // --- Balance ---

    @Test
    fun `Balance carries the currency it was built with`() {
        val b = Balance("alice", 100, "INR")
        assertEquals("INR", b.currency)
    }

    @Test
    fun `Balance with custom currency`() {
        val b = Balance("alice", -50, "USD")
        assertEquals("USD", b.currency)
        assertEquals(-50L, b.net)
    }

    @Test
    fun `Balance equality`() {
        assertEquals(Balance("a", 100, "INR"), Balance("a", 100, "INR"))
        assertNotEquals(Balance("a", 100, "INR"), Balance("a", 100, "USD"))
        assertNotEquals(Balance("a", 100, "INR"), Balance("b", 100, "INR"))
    }

    // --- DebtTransaction ---

    @Test
    fun `DebtTransaction carries the currency it was built with`() {
        val dt = DebtTransaction("bob", "alice", 500, "INR")
        assertEquals("INR", dt.currency)
    }

    @Test
    fun `DebtTransaction with custom currency`() {
        val dt = DebtTransaction("bob", "alice", 100, "EUR")
        assertEquals("EUR", dt.currency)
        assertEquals("bob", dt.from)
        assertEquals("alice", dt.to)
        assertEquals(100L, dt.amount)
    }

    @Test
    fun `DebtTransaction equality`() {
        assertEquals(
            DebtTransaction("a", "b", 100, "INR"),
            DebtTransaction("a", "b", 100, "INR")
        )
        assertNotEquals(
            DebtTransaction("a", "b", 100, "INR"),
            DebtTransaction("b", "a", 100, "INR")
        )
    }

    // --- Group ---

    @Test
    fun `Group default description is empty`() {
        val g =
            Group(
                "id",
                "name",
                createdBy = "pub",
                createdAt = 1,
                members = listOf("pub"),
                relays = listOf("wss://r.io")
            )
        assertEquals("", g.description)
    }

    @Test
    fun `Group equality`() {
        val g1 = Group("id", "name", "desc", "pub", 1, listOf("pub"), listOf("wss://r.io"))
        val g2 = Group("id", "name", "desc", "pub", 1, listOf("pub"), listOf("wss://r.io"))
        assertEquals(g1, g2)
    }

    // --- Settlement ---

    @Test
    fun `Settlement default method is cash`() {
        val s = Settlement("id", "a", "b", 100, "INR", timestamp = 1)
        assertEquals("cash", s.method)
    }
}
