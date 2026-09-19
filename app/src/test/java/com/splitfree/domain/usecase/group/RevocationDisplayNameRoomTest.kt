package com.splitfree.domain.usecase.group

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import com.splitfree.data.identity.IdentityManager
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.repository.ControlOperationJournal
import com.splitfree.data.repository.DisplayNameRepository
import com.splitfree.data.repository.GroupRepository
import com.splitfree.data.settings.UserPreferences
import com.splitfree.data.util.CompressionUtil
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GiftWrapService
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.repository.ControlOperationJournalContract
import com.splitfree.domain.repository.DisplayNameIntent
import com.splitfree.domain.repository.MembershipHistoryContract
import com.splitfree.domain.repository.SecureStorage
import com.splitfree.domain.repository.SecureStorageException
import com.splitfree.sync.event.EventPublisher
import com.splitfree.sync.event.MembershipHistory
import com.splitfree.test.FakeSecureStorage
import io.mockk.coEvery
import io.mockk.mockk
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RevocationDisplayNameRoomTest {
    private val fixtures = mutableListOf<Fixture>()

    private class FailingPreferencesContext(base: Context) : ContextWrapper(base) {
        var failCommits = false
        var throwCommits = false

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val prefs = super.getSharedPreferences(name, mode)
            if (name != "splitfree_settings") return prefs
            return object : SharedPreferences by prefs {
                override fun edit(): SharedPreferences.Editor {
                    val editor = prefs.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun putString(key: String, value: String?): SharedPreferences.Editor {
                            editor.putString(key, value)
                            return this
                        }
                        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                            editor.putBoolean(key, value)
                            return this
                        }
                        override fun commit(): Boolean {
                            val committed = editor.commit()
                            if (throwCommits) error("injected preferences commit exception")
                            return committed && !failCommits
                        }
                    }
                }
            }
        }
    }

    private inner class Fixture {
        val app: Application = RuntimeEnvironment.getApplication()
        val preferenceContext = FailingPreferencesContext(app)
        val direct = Executor { it.run() }
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor(direct).setTransactionExecutor(direct).build()
        val keyStore = FakeSecureStorage()
        var failActiveWrite = false
        var failAfterActiveWrite = false
        val identityStorage = object : SecureStorage by keyStore {
            override fun putString(key: String, value: String) {
                if (key == "nsec" && failActiveWrite) throw SecureStorageException("active write rejected")
                keyStore.putString(key, value)
                if (key == "nsec" && failAfterActiveWrite) throw SecureStorageException("active write landed")
            }
        }
        var identity = IdentityManager(app, identityStorage)
        var settings = UserPreferences(preferenceContext)
        val groups = GroupRepository(db.groupDao(), FakeSecureStorage())
        val names = DisplayNameRepository(db.displayNameDao(), db)
        val encryption = GroupEncryption(CompressionUtil)
        val journal = ControlOperationJournal(db.controlOperationDao())
        val lock = ControlOperationLock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var publisher = newPublisher()
        lateinit var original: String
        lateinit var groupId: String
        lateinit var originalIntent: DisplayNameIntent

        init {
            fixtures += this
            app.getSharedPreferences("splitfree_settings", 0).edit().clear().commit()
            identity.importKey("01".repeat(32))
            settings.giftWrapEnabled = false
        }

        fun events() = EventPublisher(
            db.eventDao(), db.outboxDao(), db.deliveryDao(), mockk(relaxed = true), mockk(relaxed = true),
            GiftWrapService(identity, settings), groups, identity, db, settings
        )

        fun revoke(
            operations: ControlOperationJournalContract = journal,
            history: MembershipHistoryContract = MembershipHistory(db.eventDao(), groups, identity)
        ) = RevokeKeyUseCase(
            identity, groups, encryption, EventSigner(identity), events(), operations, lock, settings, history
        )

        fun newPublisher(): DisplayNamePublisher {
            val update = UpdateDisplayNameUseCase(
                groups, identity, encryption, EventSigner(identity), events(), settings, names, lock, journal
            )
            return DisplayNamePublisher(identity, settings, update, groups, mockk(relaxed = true), lock, journal, scope)
        }

        suspend fun create(name: String = "Alice") {
            original = identity.getPublicKeyHex()
            originalIntent = settings.saveDisplayNameIntent(original, name)
            groupId = CreateGroupUseCase(groups, encryption, identity, EventSigner(identity), events(), settings)(
                "Trip",
                listOf("wss://nos.lol"),
                System.currentTimeMillis() / 1000 - 1000,
                original,
                "revocation-name-regression"
            ).id
            assertEquals(name.takeIf { it.isNotEmpty() }, groups.getById(groupId)!!.memberNames[original])
        }

        fun reconstruct() {
            identity = IdentityManager(app, identityStorage)
            settings = UserPreferences(preferenceContext)
            publisher = newPublisher()
        }

        suspend fun assertPublished(successor: String, expected: String) {
            assertNotEquals(original, successor)
            assertEquals(successor, identity.getPublicKeyHex())
            assertFalse(identity.hasPendingKeyPair())
            assertNull(journal.get(RotateGroupKeyUseCase.REVOCATION_ID))
            assertEquals(originalIntent, settings.getDisplayNameIntent(original))
            assertFalse(publisher.drain().needsRetry)
            val intent = checkNotNull(settings.getDisplayNameIntent(successor))
            assertEquals(expected, intent.name)
            val eventId = checkNotNull(names.committedEventId(intent, groupId))
            val stored = checkNotNull(db.eventDao().getEvent(eventId))
            val signed = checkNotNull(NostrEvent.fromJson(checkNotNull(stored.originalEventJson)))
            assertEquals(successor, signed.pubkey)
            assertTrue(signed.verify())
            val key = checkNotNull(groups.getGroupKeyForEpoch(groupId, 0))
            val meta = Json.decodeFromString<GroupMeta>(encryption.decrypt(signed.content, key))
            assertEquals(expected, meta.memberNames[successor])
            val group = checkNotNull(groups.getById(groupId))
            assertEquals(expected.takeIf { it.isNotEmpty() }, group.memberNames[successor])
            assertEquals(successor, group.createdBy)
            assertTrue(group.creatorTransitions.single().verify(groupId))
            assertEquals(expected, publisher.displayName.value)
            assertFalse(publisher.drain().needsRetry)
            assertEquals(eventId, names.committedEventId(intent, groupId))
        }
    }

    @After fun cleanup() {
        fixtures.forEach {
            it.scope.cancel()
            it.db.close()
            it.app.getSharedPreferences("splitfree_settings", 0).edit().clear().commit()
        }
    }

    @Test fun `cold publisher preserves signed name after real revocation`() = runBlocking {
        val f = Fixture()
        f.create()
        val successor = f.revoke()()
        f.reconstruct()
        f.assertPublished(successor, "Alice")
        assertEquals("", f.settings.initializeDisplayNameIntent("unrelated").name)
    }

    @Test fun `blank retiring intent does not resurrect a name`() = runBlocking {
        val f = Fixture()
        f.create("")
        f.assertPublished(f.revoke()(), "")
    }

    @Test fun `failed name commit and rollback block promotion until retry`() = runBlocking {
        val f = Fixture()
        f.create()
        f.preferenceContext.failCommits = true
        assertTrue(runCatching { f.revoke()() }.isFailure)
        val successor = checkNotNull(f.identity.getPendingPublicKeyHex())
        assertEquals(f.original, f.identity.getPublicKeyHex())
        assertNotNull(f.journal.get(RotateGroupKeyUseCase.REVOCATION_ID))
        assertThrows(IllegalStateException::class.java) { f.settings.getDisplayNameIntent(successor) }
        f.preferenceContext.failCommits = false
        f.assertPublished(f.revoke()(), "Alice")
    }

    @Test fun `throwing preference commit recovers after reconstruction`() = runBlocking {
        val f = Fixture()
        f.create()
        f.preferenceContext.throwCommits = true
        assertTrue(runCatching { f.revoke()() }.isFailure)
        assertEquals(f.original, f.identity.getPublicKeyHex())
        assertNotNull(f.journal.get(RotateGroupKeyUseCase.REVOCATION_ID))
        f.preferenceContext.throwCommits = false
        f.reconstruct()
        f.assertPublished(f.revoke()(), "Alice")
    }

    @Test fun `name transfer survives promotion failure without another successor intent`() = runBlocking {
        val f = Fixture()
        f.create()
        f.failActiveWrite = true
        assertTrue(runCatching { f.revoke()() }.isFailure)
        val successor = checkNotNull(f.identity.getPendingPublicKeyHex())
        val intent = checkNotNull(f.settings.getDisplayNameIntent(successor))
        assertEquals(f.original, f.identity.getPublicKeyHex())
        f.failActiveWrite = false
        f.reconstruct()
        f.assertPublished(f.revoke()(), "Alice")
        assertEquals(intent, f.settings.getDisplayNameIntent(successor))
    }

    private suspend fun assertRetiringEditSurvivesPromotionRetry(name: String) {
        val f = Fixture()
        f.create()
        f.failActiveWrite = true
        assertTrue(runCatching { f.revoke()() }.isFailure)
        val successor = checkNotNull(f.identity.getPendingPublicKeyHex())
        val transferred = checkNotNull(f.settings.getDisplayNameIntent(successor))
        assertEquals("Alice", transferred.name)
        assertEquals(f.original, f.identity.getPublicKeyHex())
        f.scope.cancel()
        f.publisher.submit(name)
        f.originalIntent = checkNotNull(f.settings.getDisplayNameIntent(f.original))
        assertEquals(name, f.originalIntent.name)
        assertEquals(transferred, f.settings.getDisplayNameIntent(successor))
        f.failActiveWrite = false
        f.reconstruct()
        f.assertPublished(f.revoke()(), name)
        assertNotEquals(transferred.revision, f.settings.getDisplayNameIntent(successor)?.revision)
    }

    @Test fun `retiring Bob edit refreshes successor on retry`() = runBlocking {
        assertRetiringEditSurvivesPromotionRetry("Bob")
    }

    @Test fun `retiring blank edit refreshes successor on retry`() = runBlocking {
        assertRetiringEditSurvivesPromotionRetry("")
    }

    @Test fun `active successor keeps newer name on retry`() = runBlocking {
        val f = Fixture()
        f.create()
        f.failAfterActiveWrite = true
        assertTrue(runCatching { f.revoke()() }.isFailure)
        val successor = f.identity.getPublicKeyHex()
        assertNotEquals(f.original, successor)
        f.scope.cancel()
        f.publisher.submit("Bob")
        val newer = checkNotNull(f.settings.getDisplayNameIntent(successor))
        f.failAfterActiveWrite = false
        f.reconstruct()
        f.assertPublished(f.revoke()(), "Bob")
        assertEquals(newer, f.settings.getDisplayNameIntent(successor))
    }

    @Test fun `journal retry preserves successor blank`() = runBlocking {
        val f = Fixture()
        f.create()
        val interrupted = object : ControlOperationJournalContract by f.journal {
            override suspend fun complete(id: String) {
                error("journal completion failed")
            }
        }
        assertTrue(runCatching { f.revoke(interrupted)() }.isFailure)
        val successor = f.identity.getPublicKeyHex()
        val newer = f.settings.saveDisplayNameIntent(successor, "")
        f.reconstruct()
        f.assertPublished(f.revoke()(), "")
        assertEquals(newer, f.settings.getDisplayNameIntent(successor))
    }

    @Test fun `authoritative write failure preserves name on recovery`() = runBlocking {
        val f = Fixture()
        f.create()
        val state = f.identity.observeActivePublicKey()
        f.failAfterActiveWrite = true
        assertTrue(runCatching { f.revoke()() }.isFailure)
        val successor = f.identity.getPublicKeyHex()
        assertNotEquals(f.original, successor)
        assertEquals(successor, state.value)
        assertEquals("Alice", f.settings.getDisplayNameIntent(successor)?.name)
        f.failAfterActiveWrite = false
        f.reconstruct()
        f.assertPublished(f.revoke()(), "Alice")
    }

    @Test fun `history retention failure blocks revocation publication`() = runBlocking {
        val f = Fixture()
        f.create()
        val history = mockk<MembershipHistoryContract>()
        coEvery { history.formerMembers(f.groupId) } returns emptySet()
        coEvery { history.retainRotationHistory(f.groupId) } throws IllegalStateException("history unavailable")
        val failure = runCatching { f.revoke(history = history)() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("history unavailable", failure?.message)
        assertEquals(f.original, f.identity.getPublicKeyHex())
        assertTrue(f.identity.hasPendingKeyPair())
        assertNotNull(f.journal.get(RotateGroupKeyUseCase.REVOCATION_ID))
        assertTrue(f.db.eventDao().getEventsByType(f.groupId, "key_revocation").isEmpty())
        f.assertPublished(f.revoke()(), "Alice")
    }
}
