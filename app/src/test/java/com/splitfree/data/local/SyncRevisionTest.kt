package com.splitfree.data.local

import android.app.Application
import androidx.room.Room
import com.splitfree.data.local.entities.DeliveryEntity
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.data.local.entities.GroupEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SyncRevisionTest {
    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .addCallback(AppDatabase.SYNC_REVISION_CALLBACK).allowMainThreadQueries().build()
    }

    @After
    fun teardown() = db.close()

    private suspend fun revision(groupId: String = "g"): Long = db.syncRevisionDao().observeRevision(groupId).first()

    private fun event(id: String = "event") = EventEntity(
        eventId = id, groupId = "g", pubkey = "author", createdAt = 1, kind = 30078,
        contentEncrypted = "cipher", eventType = "key_rotation", sig = "signature", receivedAt = 1,
        applyState = EventEntity.APPLY_STATE_PENDING
    )

    @Test
    fun `revision tracks evidence and replacements but not noops`() = runBlocking {
        assertEquals(0L, revision())
        db.eventDao().insert(event())
        assertEquals(1L, revision())
        val sql = db.openHelper.writableDatabase
        sql.execSQL("UPDATE events SET applyState = 0 WHERE eventId = 'event'")
        assertEquals(2L, revision())
        sql.execSQL("UPDATE events SET sig = 'restored-evidence' WHERE eventId = 'event'")
        assertEquals(3L, revision())
        sql.execSQL("UPDATE events SET sig = 'restored-evidence' WHERE eventId = 'event'")
        assertEquals(3L, revision())
        sql.execSQL("DELETE FROM events WHERE eventId = 'event'")
        db.eventDao().insert(event("replacement"))
        assertEquals(5L, revision())
        assertEquals("replacement", db.eventDao().getEvent("replacement")!!.eventId)
    }

    @Test
    fun `delivery changes advance revision independent of available count`() = runBlocking {
        val delivery = DeliveryEntity(
            "envelope",
            "g",
            "recipient",
            null,
            "encrypted-json",
            "gift_wrap",
            0,
            1,
            1,
            1,
            14
        )
        db.deliveryDao().insert(delivery)
        assertEquals(1L, revision())
        db.deliveryDao().markConsumed("envelope")
        assertEquals(2L, revision())
        db.deliveryDao().markConsumed("envelope")
        assertEquals(2L, revision())
        val sql = db.openHelper.writableDatabase
        sql.execSQL("UPDATE deliveries SET eventId = 'verified-inner' WHERE envelopeId = 'envelope'")
        assertEquals(3L, revision())
        sql.execSQL("DELETE FROM deliveries WHERE envelopeId = 'envelope'")
        assertEquals(4L, revision())
    }

    @Test
    fun `moving evidence updates revisions for both old and new groups`() = runBlocking {
        db.eventDao().insert(event())
        val sql = db.openHelper.writableDatabase
        sql.execSQL("UPDATE events SET groupId = 'other' WHERE eventId = 'event'")
        assertEquals(2L, revision("g"))
        assertEquals(1L, revision("other"))
        db.deliveryDao().insert(
            DeliveryEntity(
                "envelope",
                "g",
                "p",
                null,
                "json",
                "gift_wrap",
                0,
                1,
                1,
                1,
                4
            )
        )
        sql.execSQL("UPDATE deliveries SET groupId = 'other' WHERE envelopeId = 'envelope'")
        assertEquals(4L, revision("g"))
        assertEquals(2L, revision("other"))
    }

    @Test
    fun `roster and epoch changes advance revision while sync housekeeping and noops do not`() = runBlocking {
        db.groupDao().insert(
            GroupEntity(
                "g",
                "Trip",
                createdBy = "creator",
                createdAt = 1,
                members = "[]",
                relays = "[]"
            )
        )
        assertEquals(1L, revision())
        val sql = db.openHelper.writableDatabase
        sql.execSQL("""UPDATE groups SET keyEpoch = 1, members = '["creator"]' WHERE groupId = 'g'""")
        assertEquals(2L, revision())
        sql.execSQL("UPDATE groups SET keyEpoch = 1 WHERE groupId = 'g'")
        sql.execSQL("UPDATE groups SET lastSyncTimestamp = 42 WHERE groupId = 'g'")
        assertEquals(2L, revision())
        sql.execSQL("DELETE FROM groups WHERE groupId = 'g'")
        assertEquals(3L, revision())
    }

    @Test
    fun `group replace insert never resets a previously accumulated revision`() = runBlocking {
        val group = GroupEntity(
            "g",
            "Trip",
            createdBy = "creator",
            createdAt = 1,
            members = "[]",
            relays = "[]"
        )
        db.groupDao().insert(group)
        db.eventDao().insert(event())
        assertEquals(2L, revision())
        db.groupDao().insert(group.copy(name = "Renamed"))
        val afterFirstReplace = revision()
        // With recursive triggers REPLACE fires both DELETE and INSERT; only monotonicity is contractual.
        assertTrue(afterFirstReplace > 2L)
        db.groupDao().insert(group.copy(name = "Renamed again"))
        assertTrue(revision() > afterFirstReplace)
        assertEquals("Renamed again", db.groupDao().getById("g")!!.name)
    }
}
