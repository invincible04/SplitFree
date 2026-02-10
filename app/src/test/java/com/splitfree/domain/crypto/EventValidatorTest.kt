package com.splitfree.domain.crypto

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for EventValidator timestamp validation.
 * Design doc Section 13.7.
 */
class EventValidatorTest {

    private fun nowSecs() = System.currentTimeMillis() / 1000

    // --- Standard validation (isTimestampValid) ---

    @Test
    fun `accepts current timestamp`() {
        assertTrue(EventValidator.isTimestampValid(nowSecs()))
    }

    @Test
    fun `accepts timestamp 1 minute ago`() {
        assertTrue(EventValidator.isTimestampValid(nowSecs() - 60))
    }

    @Test
    fun `accepts timestamp 29 days ago`() {
        assertTrue(EventValidator.isTimestampValid(nowSecs() - 29 * 86400))
    }

    @Test
    fun `rejects timestamp 31 days ago`() {
        assertFalse(EventValidator.isTimestampValid(nowSecs() - 31 * 86400))
    }

    @Test
    fun `accepts timestamp 30 minutes in future`() {
        assertTrue(EventValidator.isTimestampValid(nowSecs() + 1800))
    }

    @Test
    fun `rejects timestamp 2 hours in future`() {
        assertFalse(EventValidator.isTimestampValid(nowSecs() + 7200))
    }

    @Test
    fun `rejects year 2030 timestamp`() {
        assertFalse(EventValidator.isTimestampValid(1893456000L))
    }

    @Test
    fun `rejects epoch 0`() {
        assertFalse(EventValidator.isTimestampValid(0L))
    }

    @Test
    fun `rejects negative timestamp`() {
        assertFalse(EventValidator.isTimestampValid(-1L))
    }

    // --- Lenient validation (isTimestampValidLenient) ---

    @Test
    fun `lenient accepts current timestamp`() {
        assertTrue(EventValidator.isTimestampValidLenient(nowSecs()))
    }

    @Test
    fun `lenient accepts 1 year old timestamp`() {
        assertTrue(EventValidator.isTimestampValidLenient(nowSecs() - 365 * 86400))
    }

    @Test
    fun `lenient accepts epoch 0`() {
        assertTrue(EventValidator.isTimestampValidLenient(0L))
    }

    @Test
    fun `lenient rejects 2 hours in future`() {
        assertFalse(EventValidator.isTimestampValidLenient(nowSecs() + 7200))
    }

    @Test
    fun `lenient accepts 30 minutes in future`() {
        assertTrue(EventValidator.isTimestampValidLenient(nowSecs() + 1800))
    }

    // --- Boundary: exactly at the 1-hour future limit ---

    @Test
    fun `boundary - exactly 1 hour future is accepted`() {
        assertTrue(EventValidator.isTimestampValid(nowSecs() + 3600))
    }

    @Test
    fun `boundary - 1 hour + 1 second future is rejected`() {
        assertFalse(EventValidator.isTimestampValid(nowSecs() + 3601))
    }
}
