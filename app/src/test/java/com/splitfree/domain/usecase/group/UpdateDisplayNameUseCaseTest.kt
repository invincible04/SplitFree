package com.splitfree.domain.usecase.group

import android.app.Application
import androidx.room.Room
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.repository.ControlOperationJournal
import com.splitfree.data.repository.DisplayNameRepository
import com.splitfree.data.repository.GroupRepository
import com.splitfree.data.settings.UserPreferences
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GiftWrapService
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.repository.DisplayNameClockDeferredException
import com.splitfree.domain.repository.DisplayNameDelivery
import com.splitfree.domain.repository.DisplayNameIntent
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.PreparedDisplayName
import com.splitfree.sync.event.EventPublisher
import com.splitfree.test.FakeSecureStorage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class UpdateDisplayNameUseCaseTest {
    private val me = "aa".repeat(32)
    private val other = "bb".repeat(32)
    private val identity = mockk<IdentityContract>(relaxed = true)
    private val encryption = mockk<GroupEncryption>()
    private val signer = mockk<EventSigner>()
    private val transport = mockk<EventPublisherContract>()
    private val keyStore = FakeSecureStorage()
    private val databaseName = "names-${UUID.randomUUID()}"
    private lateinit var db: AppDatabase
    private lateinit var groups: GroupRepository
    private lateinit var names: DisplayNameRepository
    private lateinit var settings: UserPreferences
    private lateinit var publisher: EventPublisher
    private lateinit var useCase: UpdateDisplayNameUseCase
    private val signed = mutableListOf<NostrEvent>()
    private val group =
        Group("g", "Trip", createdBy = me, createdAt = 1, members = listOf(me, other), relays = emptyList())

    @Before
    fun setup(): Unit = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("splitfree_settings", 0).edit().clear().commit()
        settings = UserPreferences(app)
        every { identity.hasIdentity() } returns true
        every { identity.hasPendingKeyPair() } returns false
        every { identity.stagedIdentitySwitch() } returns null
        every { identity.getPublicKeyHex() } returns me
        every { encryption.encrypt(any(), any()) } answers { "encrypted:${firstArg<String>()}" }
        every { signer.createSignedEvent(any(), any(), any(), any(), any(), any()) } answers {
            NostrEvent(
                id = (if (signed.isEmpty()) "f" else "0").repeat(63) + signed.size.toString(16),
                pubkey = me,
                createdAt = checkNotNull(arg<Long?>(5)),
                kind = 30078,
                tags = listOf(listOf("g", firstArg()), listOf("t", "group_meta")),
                content = thirdArg(),
                sig = "sig"
            ).also { signed += it }
        }
        openDatabase()
        groups.save(group, "key")
        coEvery { transport.publishDisplayName(any()) } coAnswers { publisher.publishDisplayName(firstArg()) }
    }

    private fun openDatabase() {
        db = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java, databaseName)
            .allowMainThreadQueries().build()
        groups = GroupRepository(db.groupDao(), keyStore)
        names = DisplayNameRepository(db.displayNameDao(), db)
        publisher = EventPublisher(
            db.eventDao(), db.outboxDao(), db.deliveryDao(), mockk(relaxed = true), mockk(relaxed = true),
            mockk<GiftWrapService>(relaxed = true), groups, identity, db, settings
        )
        useCase = UpdateDisplayNameUseCase(
            groups, identity, encryption, signer, transport, settings, names, ControlOperationLock(),
            ControlOperationJournal(db.controlOperationDao())
        )
    }

    private fun intent(name: String): DisplayNameIntent = settings.saveDisplayNameIntent(me, name)

    @After fun cleanup() = db.close()

    @Test fun `same-second renames reserve increasing clocks despite descending event IDs`() = runBlocking {
        useCase(intent("Bob"))
        useCase(intent("Alice"))
        assertEquals("Alice", groups.getById("g")!!.memberNames[me])
        assertEquals(2, signed.size)
        assertTrue(signed[1].createdAt > signed[0].createdAt)
        assertTrue(signed[1].id < signed[0].id)
    }

    @Test fun `atomic publication saves own projection event outbox and completion`() = runBlocking {
        val desired = intent("Alice")
        val result = useCase(desired)
        assertEquals(mapOf("g" to DisplayNameDelivery.DURABLY_QUEUED), result.groups)
        assertFalse(result.needsRetry)
        assertEquals("Alice", groups.getById("g")!!.memberNames[me])
        assertNotNull(db.eventDao().getEvent(signed.single().id))
        assertEquals(signed.single().toJson(), db.outboxDao().getAll().single().eventJson)
        assertEquals(signed.single().id, names.committedEventId(desired, "g"))
    }

    @Test fun `failure at completion rolls back projection event and outbox and retains preparation`() = runBlocking {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_name_completion BEFORE UPDATE ON display_name_publications " +
                "WHEN NEW.committedEventId IS NOT NULL BEGIN SELECT RAISE(ABORT, 'injected'); END"
        )
        val desired = intent("Alice")
        assertEquals(setOf("g"), useCase(desired).deferredGroups)
        assertNull(groups.getById("g")!!.memberNames[me])
        assertNull(db.eventDao().getEvent(signed.single().id))
        assertEquals(0, db.outboxDao().count())
        assertNull(names.committedEventId(desired, "g"))
        assertNotNull(names.getPrepared(desired, "g"))
    }

    @Test fun `process loss after preparation retries byte-identical event without reserving again`() = runBlocking {
        coEvery { transport.publishDisplayName(any()) } throws CancellationException("process lost")
        val desired = intent("Alice")
        try {
            useCase(desired)
            error("Expected cancellation")
        } catch (_: CancellationException) {
            assertNotNull(names.getPrepared(desired, "g"))
        }
        val prepared = names.getPrepared(desired, "g")!!.event.toJson()
        db.close()
        openDatabase()
        coEvery { transport.publishDisplayName(any()) } coAnswers { publisher.publishDisplayName(firstArg()) }
        assertFalse(useCase(desired).needsRetry)
        assertEquals(prepared, db.outboxDao().getAll().single().eventJson)
        assertEquals(1, signed.size)
    }

    @Test fun `partial group failure is deferred and successful group is not duplicated on retry`() = runBlocking {
        groups.save(group.copy(id = "h"), "key")
        var fail = true
        coEvery { transport.publishDisplayName(any()) } coAnswers {
            val operation = firstArg<PreparedDisplayName>()
            if (operation.group.id == "h" && fail) error("temporary storage failure")
            publisher.publishDisplayName(operation)
        }
        val desired = intent("Alice")
        val partial = useCase(desired)
        assertEquals(setOf("h"), partial.deferredGroups)
        assertEquals(setOf("g"), partial.groups.keys)
        fail = false
        assertFalse(useCase(desired).needsRetry)
        assertEquals(2, db.outboxDao().count())
        assertEquals(2, signed.size)
    }

    @Test fun `missing current epoch key remains pending without a local projection`() = runBlocking {
        groups.updateKeyEpoch("g", 2)
        val result = useCase(intent("Alice"))
        assertEquals(setOf("g"), result.deferredGroups)
        assertEquals(0, signed.size)
        assertNull(groups.getById("g")!!.memberNames[me])
    }

    @Test fun `clock reservation persists after restart and respects meta watermark`() = runBlocking {
        val desired = intent("Alice")
        val now = System.currentTimeMillis() / 1000
        assertEquals(now + 21, names.reserveTimestamp(desired, "g", now + 20, now))
        db.close()
        openDatabase()
        assertEquals(now + 22, names.reserveTimestamp(desired, "g", 0, now - 10))
        assertEquals(now - 10, names.reserveTimestamp(desired.copy(identityPubkey = other), "g", 0, now - 10))
    }

    @Test fun `future clock bound fails closed without reducing the durable floor`() = runBlocking {
        val desired = intent("Alice")
        val now = System.currentTimeMillis() / 1000
        assertEquals(now + 3600, names.reserveTimestamp(desired, "g", now + 3599, now))
        try {
            names.reserveTimestamp(desired, "g", 0, now)
            error("Expected defer")
        } catch (_: DisplayNameClockDeferredException) {
            assertEquals(now + 3600, db.displayNameDao().getPublication(me, "g")!!.lastReservedTimestamp)
        }
    }

    @Test fun `epoch change after preparation refuses stale ciphertext and reprepares on retry`() = runBlocking {
        var rotate = true
        coEvery { transport.publishDisplayName(any()) } coAnswers {
            if (rotate) {
                rotate = false
                groups.saveGroupKeyForEpoch("g", 1, "next-key")
                groups.updateKeyEpoch("g", 1)
            }
            publisher.publishDisplayName(firstArg())
        }
        val desired = intent("Alice")
        assertTrue(useCase(desired).needsRetry)
        assertEquals(0, db.outboxDao().count())
        assertFalse(useCase(desired).needsRetry)
        assertEquals(1, db.eventDao().getEvent(signed.last().id)!!.keyEpoch)
        val meta = Json.decodeFromString<GroupMeta>(signed.last().content.removePrefix("encrypted:"))
        assertEquals(1, meta.keyEpoch)
        verify { encryption.encrypt(any(), "next-key") }
    }

    @Test fun `new desired name observed before commit prevents stale local mutation`() = runBlocking {
        coEvery { transport.publishDisplayName(any()) } coAnswers {
            intent("Carol")
            publisher.publishDisplayName(firstArg())
        }
        assertTrue(useCase(intent("Bob")).superseded)
        assertEquals("Carol", settings.displayName)
        assertNull(groups.getById("g")!!.memberNames[me])
        assertEquals(0, db.outboxDao().count())
    }

    @Test fun `identity change before commit refuses old-author event`() = runBlocking {
        coEvery { transport.publishDisplayName(any()) } coAnswers {
            every { identity.getPublicKeyHex() } returns other
            publisher.publishDisplayName(firstArg())
        }
        assertTrue(useCase(intent("Alice")).superseded)
        assertEquals(0, db.outboxDao().count())
        assertNull(groups.getById("g")!!.memberNames[me])
    }

    @Test fun `new groups are included even when existing groups already completed`() = runBlocking {
        val desired = intent("Alice")
        useCase(desired)
        groups.save(group.copy(id = "h"), "key")
        assertEquals(setOf("g", "h"), useCase(desired).groups.keys)
        assertEquals(2, signed.size)
    }

    @Test fun `blank name is explicit in metadata and clears only own display name`() = runBlocking {
        groups.save(group.copy(memberNames = mapOf(me to "Old", other to "Other")), "key")
        useCase(intent("  "))
        val meta = Json.decodeFromString<GroupMeta>(signed.single().content.removePrefix("encrypted:"))
        assertEquals("", meta.memberNames[me])
        assertEquals(mapOf(other to "Other"), groups.getById("g")!!.memberNames)
    }

    @Test fun `outbox removal never implies relay acknowledgment`() = runBlocking {
        val desired = intent("Alice")
        useCase(desired)
        db.outboxDao().delete(signed.single().id)
        assertEquals(DisplayNameDelivery.DURABLY_QUEUED, useCase(desired).groups["g"])
        assertEquals(1, signed.size)
    }
}
