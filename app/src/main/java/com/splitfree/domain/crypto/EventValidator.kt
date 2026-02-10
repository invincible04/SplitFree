package com.splitfree.domain.crypto

/**
 * Validates event timestamps per design doc Section 13.7.
 * Rejects events with timestamps too far in the future (>1 hour)
 * to prevent timestamp manipulation attacks on event ordering.
 */
object EventValidator {
    private const val MAX_FUTURE_SECS = 3600L       // 1 hour
    private const val MAX_AGE_SECS = 30L * 86400L   // 30 days

    fun isTimestampValid(createdAtSecs: Long): Boolean {
        val now = System.currentTimeMillis() / 1000
        return createdAtSecs in (now - MAX_AGE_SECS)..(now + MAX_FUTURE_SECS)
    }
}
