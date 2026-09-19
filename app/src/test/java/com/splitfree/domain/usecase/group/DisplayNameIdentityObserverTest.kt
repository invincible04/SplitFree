package com.splitfree.domain.usecase.group

import android.app.Application
import com.splitfree.data.identity.IdentityManager
import com.splitfree.data.settings.UserPreferences
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.ControlOperationJournalContract
import com.splitfree.domain.repository.DisplayNamePublishResult
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.util.hexToBytes
import com.splitfree.test.FakeSecureStorage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DisplayNameIdentityObserverTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val identity = IdentityManager(app, FakeSecureStorage())
    private val settings = UserPreferences(app)
    private val update = mockk<UpdateDisplayNameUseCase>()
    private val groups = mockk<GroupRepositoryContract>()
    private val journal = mockk<ControlOperationJournalContract>()
    private val successorKey = "02".repeat(32)
    private val successor = NostrEvent.pubkeyFromPrivkey(successorKey.hexToBytes())

    @Before fun setup() {
        app.getSharedPreferences("splitfree_settings", 0).edit().clear().commit()
        identity.importKey("01".repeat(32))
        settings.saveDisplayNameIntent(identity.getPublicKeyHex(), "Alice")
        every { groups.observeAll() } returns MutableStateFlow<List<Group>>(emptyList())
        coEvery { journal.get(any()) } returns null
        coEvery { update(any()) } returns DisplayNamePublishResult()
    }

    @Test fun `real identity import wakes publication and visible name without roster change`() = runTest {
        val original = identity.getPublicKeyHex()
        val desired = settings.saveDisplayNameIntent(successor, "Bob")
        val publisher = DisplayNamePublisher(
            identity,
            settings,
            update,
            groups,
            mockk(relaxed = true),
            ControlOperationLock(),
            journal,
            backgroundScope
        )
        publisher.start()
        runCurrent()
        advanceTimeBy(801)
        runCurrent()
        assertEquals("Alice", publisher.displayName.value)
        val presence = identity.observeHasIdentity()
        identity.importKey(successorKey)
        runCurrent()
        advanceTimeBy(801)
        runCurrent()
        assertTrue(presence.value)
        coVerify(exactly = 1) { update(desired) }
        assertEquals("Bob", publisher.displayName.value)
        repeat(4) { identity.getPublicKeyHex() }
        identity.importKey(successorKey)
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        coVerify(exactly = 1) { update(desired) }
        assertEquals("Alice", settings.displayNameFor(original))
    }

    @Test fun `real unrelated import initializes blank instead of transferring previous identity name`() = runTest {
        val publisher = DisplayNamePublisher(
            identity,
            settings,
            update,
            groups,
            mockk(relaxed = true),
            ControlOperationLock(),
            journal,
            backgroundScope
        )
        publisher.start()
        runCurrent()
        advanceTimeBy(801)
        runCurrent()
        identity.importKey(successorKey)
        runCurrent()
        advanceTimeBy(801)
        runCurrent()
        assertEquals("", publisher.displayName.value)
        coVerify(exactly = 1) { update(match { it.identityPubkey == successor && it.name.isEmpty() }) }
    }

    @Test fun `real staged switch wakes existing successor intent after completion under control lock`() = runTest {
        val desired = settings.saveDisplayNameIntent(successor, "Bob")
        val lock = ControlOperationLock()
        val publisher = DisplayNamePublisher(
            identity,
            settings,
            update,
            groups,
            mockk(relaxed = true),
            lock,
            journal,
            backgroundScope
        )
        publisher.start()
        runCurrent()
        advanceTimeBy(801)
        runCurrent()
        lock.withLock {
            val staged = identity.stageIdentitySwitch(successorKey)
            identity.commitIdentitySwitch(staged)
            identity.completeIdentitySwitch(staged)
        }
        runCurrent()
        advanceTimeBy(801)
        runCurrent()
        coVerify(exactly = 1) { update(desired) }
        assertEquals("Bob", publisher.displayName.value)
    }
}
