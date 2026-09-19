package com.splitfree.data.local.dao

import android.app.Application
import androidx.room.Room
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.local.entities.OutboxEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Runs the outbox retention and health queries against a real in-memory Room database, so the
 * SQL (not a mock of it) is what decides which user-authored rows survive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class OutboxDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: OutboxDao

    private val now = 1_700_000_000L
    private val day = 86_400L

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.outboxDao()
    }

    @After
    fun tearDown() = db.close()

    private fun row(
        id: String,
        createdAt: Long,
        lastRetryAt: Long? = null,
        retryCount: Int = 0,
        eventType: String? = "expense"
    ) = OutboxEntity(id, """{"id":"$id"}""", createdAt, retryCount, lastRetryAt, eventType)

    @Test
    fun `deleteOlderThan keeps an old event that was retried recently`() = runBlocking {
        // Authored 100 days ago (offline for a long time), but a relay attempt happened yesterday.
        dao.insert(row("recent-attempt", createdAt = now - 100 * day, lastRetryAt = now - day, retryCount = 3))

        dao.deleteOlderThan(now - 90 * day)

        assertEquals(listOf("recent-attempt"), dao.getAll().map { it.eventId })
    }

    @Test
    fun `deleteOlderThan removes a row whose last attempt is past the cutoff`() = runBlocking {
        dao.insert(row("abandoned", createdAt = now - 200 * day, lastRetryAt = now - 91 * day, retryCount = 60))

        dao.deleteOlderThan(now - 90 * day)

        assertEquals(emptyList<String>(), dao.getAll().map { it.eventId })
    }

    @Test
    fun `deleteOlderThan falls back to createdAt for rows never attempted`() = runBlocking {
        dao.insert(row("never-tried-old", createdAt = now - 91 * day, lastRetryAt = null))
        dao.insert(row("never-tried-new", createdAt = now - 89 * day, lastRetryAt = null))

        dao.deleteOlderThan(now - 90 * day)

        assertEquals(listOf("never-tried-new"), dao.getAll().map { it.eventId })
    }

    @Test
    fun `deleteOlderThan never removes critical types`() = runBlocking {
        dao.insert(row("meta", createdAt = now - 400 * day, lastRetryAt = now - 300 * day, eventType = "group_meta"))
        dao.insert(row("rot", createdAt = now - 400 * day, lastRetryAt = now - 300 * day, eventType = "key_rotation"))
        dao.insert(row("rev", createdAt = now - 400 * day, lastRetryAt = now - 300 * day, eventType = "key_revocation"))
        dao.insert(
            row("history", createdAt = now - 400 * day, lastRetryAt = now - 300 * day, eventType = "identity_history")
        )
        dao.insert(row("history-never-tried", createdAt = now - 400 * day, eventType = "identity_history"))
        dao.insert(row("exp", createdAt = now - 400 * day, lastRetryAt = now - 300 * day, eventType = "expense"))
        dao.insert(row("untyped", createdAt = now - 400 * day, lastRetryAt = now - 300 * day, eventType = null))

        dao.deleteOlderThan(now - 90 * day)

        assertEquals(
            setOf("meta", "rot", "rev", "history", "history-never-tried"),
            dao.getAll().map {
                it.eventId
            }.toSet()
        )
    }

    @Test
    fun `pendingOutboxCount and stuckOutboxCount reflect the table`() = runBlocking {
        assertEquals(0, dao.pendingOutboxCount().first())
        assertEquals(0, dao.stuckOutboxCount().first())

        dao.insert(row("fresh", createdAt = now, retryCount = 0))
        dao.insert(row("struggling", createdAt = now, retryCount = 49))
        dao.insert(row("stuck", createdAt = now, retryCount = 50))
        dao.insert(row("very-stuck", createdAt = now, retryCount = 500))

        assertEquals(4, dao.pendingOutboxCount().first())
        assertEquals(2, dao.stuckOutboxCount().first())

        dao.delete("stuck")

        assertEquals(3, dao.pendingOutboxCount().first())
        assertEquals(1, dao.stuckOutboxCount().first())
    }

    @Test
    fun `incrementRetry advances both count and timestamp`() = runBlocking {
        dao.insert(row("e1", createdAt = now))

        dao.incrementRetry("e1", now + 5)
        dao.incrementRetry("e1", now + 10)

        val stored = dao.getAll().single()
        assertEquals(2, stored.retryCount)
        assertEquals(now + 10, stored.lastRetryAt)
    }

    @Test
    fun `inserting an already queued event id does not reset its retry state`() = runBlocking {
        dao.insert(row("e1", createdAt = now, eventType = "expense"))
        dao.incrementRetry("e1", now + 5)
        dao.incrementRetry("e1", now + 10)

        // A re-commit of the same event (fresh row, retryCount 0, no lastRetryAt) must be ignored.
        dao.insert(row("e1", createdAt = now + 100, eventType = "group_meta"))

        val stored = dao.getAll().single()
        assertEquals(2, stored.retryCount)
        assertEquals(now + 10, stored.lastRetryAt)
        assertEquals(now, stored.createdAt)
        assertEquals("expense", stored.eventType)
        assertEquals(1, dao.count())
    }
}
