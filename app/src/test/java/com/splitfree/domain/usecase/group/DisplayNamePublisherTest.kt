package com.splitfree.domain.usecase.group

import com.splitfree.domain.repository.ControlOperation
import com.splitfree.domain.repository.ControlOperationJournalContract
import com.splitfree.domain.repository.DisplayNameIntent
import com.splitfree.domain.repository.DisplayNamePublishResult
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.SettingsContract
import com.splitfree.sync.worker.DisplayNameScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DisplayNamePublisherTest {
    private val identity = mockk<IdentityContract>(relaxed = true)
    private val settings = mockk<SettingsContract>()
    private val update = mockk<UpdateDisplayNameUseCase>()
    private val groups = mockk<GroupRepositoryContract>()
    private val scheduler = mockk<DisplayNameScheduler>(relaxed = true)
    private val journal = mockk<ControlOperationJournalContract>(relaxed = true)
    private val lock = ControlOperationLock()
    private var desired = DisplayNameIntent("me", "0", "Alice", 1)
    private var sequence = 0

    @Before fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { identity.hasIdentity() } returns true
        every { identity.observeActivePublicKey() } returns kotlinx.coroutines.flow.MutableStateFlow("me")
        every { identity.stagedIdentitySwitch() } returns null
        every { identity.getPublicKeyHex() } returns "me"
        every { settings.displayName } answers { desired.name }
        every { settings.initializeDisplayNameIntent("me") } answers { desired }
        every { settings.saveDisplayNameIntent("me", any()) } answers {
            desired = DisplayNameIntent("me", (++sequence).toString(), secondArg(), sequence.toLong())
            desired
        }
        every { groups.observeAll() } returns flowOf(emptyList())
        coEvery { journal.get(any()) } returns null
        coEvery { update(any()) } returns DisplayNamePublishResult()
    }

    @After fun cleanup() = unmockkStatic(android.util.Log::class)

    @Test fun `intent is persisted synchronously before debounce or app scope execution`() = runTest {
        val publisher =
            DisplayNamePublisher(identity, settings, update, groups, scheduler, lock, journal, backgroundScope)
        publisher.submit("Bob")
        assertEquals("Bob", desired.name)
        assertEquals("Bob", publisher.displayName.value)
        coVerify(exactly = 0) { update(any()) }
        runCurrent()
        advanceTimeBy(801)
        runCurrent()
        coVerify(exactly = 1) { update(match { it.name == "Bob" }) }
    }

    @Test fun `startup retries persisted uncompleted intent without needing a settings edit`() = runTest {
        val publisher =
            DisplayNamePublisher(identity, settings, update, groups, scheduler, lock, journal, backgroundScope)
        publisher.start()
        runCurrent()
        advanceTimeBy(801)
        runCurrent()
        coVerify(exactly = 1) { update(desired) }
        verify(exactly = 1) { scheduler.scheduleRecovery() }
    }

    @Test fun `start from several viewmodels owns just one collector and recovery registration`() = runTest {
        val publisher =
            DisplayNamePublisher(identity, settings, update, groups, scheduler, lock, journal, backgroundScope)
        repeat(4) { publisher.start() }
        runCurrent()
        advanceTimeBy(801)
        runCurrent()
        coVerify(exactly = 1) { update(any()) }
        verify(exactly = 1) { scheduler.scheduleRecovery() }
    }

    @Test fun `simultaneous workers serialize broadcasts and newest desired name runs last`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val completed = mutableListOf<String>()
        var inFlight = 0
        var maxInFlight = 0
        coEvery { update(any()) } coAnswers {
            val intent = firstArg<DisplayNameIntent>()
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            if (intent.name == "Bob") {
                entered.complete(Unit)
                release.await()
            }
            completed += intent.name
            inFlight--
            DisplayNamePublishResult()
        }
        desired = desired.copy(revision = "1", name = "Bob")
        val publisher =
            DisplayNamePublisher(identity, settings, update, groups, scheduler, lock, journal, backgroundScope)
        val oldWorker = async { publisher.drain() }
        entered.await()
        desired = desired.copy(revision = "2", name = "Carol")
        val newWorker = async { publisher.drain() }
        runCurrent()
        assertEquals(1, maxInFlight)
        release.complete(Unit)
        oldWorker.await()
        newWorker.await()
        assertEquals(listOf("Bob", "Carol", "Carol"), completed)
        assertEquals(1, maxInFlight)
        assertEquals("Carol", publisher.displayName.value)
        verify(exactly = 0) { settings.saveDisplayNameIntent(any(), any()) }
    }

    @Test fun `an edit during slow publication coalesces directly to latest trailing name`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val completed = mutableListOf<String>()
        coEvery { update(any()) } coAnswers {
            val intent = firstArg<DisplayNameIntent>()
            if (intent.name == "Bob") {
                entered.complete(Unit)
                release.await()
            }
            completed += intent.name
            DisplayNamePublishResult()
        }
        val publisher =
            DisplayNamePublisher(identity, settings, update, groups, scheduler, lock, journal, backgroundScope)
        publisher.submit("Bob")
        runCurrent()
        advanceTimeBy(801)
        runCurrent()
        entered.await()
        publisher.submit("Carol")
        publisher.submit("Dave")
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf("Bob", "Dave"), completed)
        assertEquals("Dave", desired.name)
    }

    @Test fun `partial outcome remains visible and durable retry is scheduled without busy loop`() = runTest {
        coEvery { update(any()) } returns DisplayNamePublishResult(deferredGroups = setOf("g"))
        val publisher =
            DisplayNamePublisher(identity, settings, update, groups, scheduler, lock, journal, backgroundScope)
        publisher.start()
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(publisher.result.value.needsRetry)
        coVerify(exactly = 1) { update(any()) }
        verify(atLeast = 2) { scheduler.requestDrain() }
    }

    @Test fun `durable switch journal prevents legacy preference migration and signing`() = runTest {
        coEvery { journal.get(IdentitySwitchCoordinator.SWITCH_ID) } returns ControlOperation("switch", "switch", "{}")
        val publisher =
            DisplayNamePublisher(identity, settings, update, groups, scheduler, lock, journal, backgroundScope)
        try {
            publisher.drain()
            error("Expected switch deferral")
        } catch (_: IllegalStateException) {
            coVerify(exactly = 0) { update(any()) }
            verify(exactly = 0) { settings.initializeDisplayNameIntent(any()) }
        }
    }

    @Test fun `reverting during a slow broadcast still publishes the original desired name last`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val completed = mutableListOf<String>()
        coEvery { update(any()) } coAnswers {
            val intent = firstArg<DisplayNameIntent>()
            if (intent.name == "Bob") {
                entered.complete(Unit)
                release.await()
            }
            completed += intent.name
            DisplayNamePublishResult()
        }
        desired = desired.copy(revision = "1", name = "Bob")
        val publisher =
            DisplayNamePublisher(identity, settings, update, groups, scheduler, lock, journal, backgroundScope)
        val worker = async { publisher.drain() }
        entered.await()
        desired = desired.copy(revision = "2", name = "Alice")
        release.complete(Unit)
        worker.await()
        assertEquals(listOf("Bob", "Alice"), completed)
        assertEquals("Alice", publisher.displayName.value)
    }

    @Test fun `a failed broadcast stops and a later edit restarts it without overwriting desired name`() = runTest {
        coEvery { update(match { it.name == "Bob" }) } throws IllegalStateException("offline")
        val publisher =
            DisplayNamePublisher(identity, settings, update, groups, scheduler, lock, journal, backgroundScope)
        publisher.submit("Bob")
        runCurrent()
        advanceTimeBy(801)
        runCurrent()
        publisher.submit("Carol")
        runCurrent()
        advanceTimeBy(801)
        runCurrent()
        coVerify(exactly = 1) { update(match { it.name == "Bob" }) }
        coVerify(exactly = 1) { update(match { it.name == "Carol" }) }
        assertEquals("Carol", desired.name)
    }
}
