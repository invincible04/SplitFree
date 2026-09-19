package com.splitfree.data.identity

import android.content.Context
import android.content.SharedPreferences
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.nip.Bip39
import com.splitfree.domain.repository.IdentityState
import com.splitfree.domain.repository.SecureStorage
import com.splitfree.domain.repository.SecureStorageException
import com.splitfree.domain.repository.SecureStorageKeyLostException
import com.splitfree.domain.util.hexToBytes
import com.splitfree.test.FakeSecureStorage
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IdentityManagerTest {
    private val context = mockk<Context>(relaxed = true)
    private lateinit var storage: FakeSecureStorage
    private lateinit var mgr: IdentityManager

    // A valid secp256k1 private key (known to pass secKeyVerify)
    private val validPrivHex = "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a"
    private val validPubHex = NostrEvent.pubkeyFromPrivkey(validPrivHex.hexToBytes())

    // A second valid key, used wherever a test needs a distinct pending / imported identity.
    private val otherPrivHex = "1111111111111111111111111111111111111111111111111111111111111111"
    private val otherPubHex = NostrEvent.pubkeyFromPrivkey(otherPrivHex.hexToBytes())

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>(), any()) } returns 0

        storage = FakeSecureStorage()
        mgr = IdentityManager(context, storage)
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `hasIdentity true`() {
        storage.putString("nsec", "key")
        assertTrue(mgr.hasIdentity())
    }

    @Test
    fun `hasIdentity false`() {
        assertFalse(mgr.hasIdentity())
    }

    @Test
    fun `hasIdentity false when key is present but cannot be decrypted`() {
        val lostKeyStorage = mockk<SecureStorage>()
        every { lostKeyStorage.contains("nsec") } returns true
        every { lostKeyStorage.canDecrypt("nsec") } returns false
        assertFalse(IdentityManager(context, lostKeyStorage).hasIdentity())
        verify(exactly = 1) { lostKeyStorage.canDecrypt("nsec") }
    }

    @Test
    fun `hasIdentity skips decrypt attempt when key is absent`() {
        val emptyStorage = mockk<SecureStorage>()
        every { emptyStorage.contains("nsec") } returns false
        assertFalse(IdentityManager(context, emptyStorage).hasIdentity())
        verify(exactly = 0) { emptyStorage.canDecrypt(any()) }
    }

    // --- observeHasIdentity ---

    @Test
    fun `observeHasIdentity starts false without a key`() {
        assertFalse(mgr.observeHasIdentity().value)
    }

    @Test
    fun `observeHasIdentity starts true when a key is already stored`() {
        storage.putString("nsec", validPrivHex)
        assertTrue(mgr.observeHasIdentity().value)
    }

    @Test
    fun `observeHasIdentity emits true after generateKeyPair`() {
        val state = mgr.observeHasIdentity()
        assertFalse(state.value)
        mgr.generateKeyPair()
        assertTrue(state.value)
    }

    @Test
    fun `observeHasIdentity emits true after importKey`() {
        val state = mgr.observeHasIdentity()
        assertFalse(state.value)
        mgr.importKey(validPrivHex)
        assertTrue(state.value)
    }

    @Test
    fun `observeHasIdentity stays false when importKey rejects the input`() {
        val state = mgr.observeHasIdentity()
        assertThrows(IllegalArgumentException::class.java) { mgr.importKey("00".repeat(32)) }
        assertFalse(state.value)
    }

    @Test
    fun `observeHasIdentity emits true after commitPendingKeyPair`() {
        val state = mgr.observeHasIdentity()
        storage.putString("nsec_pending", otherPrivHex)
        storage.putString("npub_pending", otherPubHex)
        assertFalse(state.value)
        mgr.commitPendingKeyPair()
        assertTrue(state.value)
    }

    @Test
    fun `observeHasIdentity is not evaluated until requested`() {
        val lazyStorage = mockk<SecureStorage>()
        every { lazyStorage.contains("nsec") } returns true
        every { lazyStorage.canDecrypt("nsec") } returns true
        IdentityManager(context, lazyStorage)
        verify(exactly = 0) { lazyStorage.contains(any()) }
        verify(exactly = 0) { lazyStorage.canDecrypt(any()) }
    }

    @Test
    fun `observeHasIdentity delivers false then true to a live collector`() = runTest {
        val state = mgr.observeHasIdentity()
        val seen = mutableListOf<Boolean>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { state.take(2).toList(seen) }
        mgr.generateKeyPair()
        job.join()
        assertEquals(listOf(false, true), seen)
    }

    @Test
    fun `active key observation is lazy and seeds from the durable private key`() {
        val spy = spyk(FakeSecureStorage().also { it.putString("nsec", validPrivHex) })
        val manager = IdentityManager(context, spy)
        verify(exactly = 0) { spy.getString(any(), any()) }
        assertEquals(validPubHex, manager.observeActivePublicKey().value)
    }

    @Test
    fun `active key emits for imports staged switches and promotion but not same key or reads`() = runTest {
        val seen = mutableListOf<String?>()
        val state = mgr.observeActivePublicKey()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.toList(seen) }
        mgr.importKey(validPrivHex)
        val presence = mgr.observeHasIdentity()
        mgr.importKey(otherPrivHex)
        mgr.importKey(otherPrivHex)
        repeat(3) { mgr.getPublicKeyHex() }
        val staged = mgr.stageIdentitySwitch(validPrivHex)
        mgr.commitIdentitySwitch(staged)
        mgr.completeIdentitySwitch(staged)
        storage.putString("nsec_pending", otherPrivHex)
        mgr.finishPendingKeyPair(otherPubHex)
        mgr.finishPendingKeyPair(otherPubHex)
        assertTrue(presence.value)
        assertEquals(listOf(null, validPubHex, otherPubHex, validPubHex, otherPubHex), seen)
    }

    @Test
    fun `generated replacement updates active key even while presence stays true`() {
        mgr.importKey(validPrivHex)
        val state = mgr.observeActivePublicKey()
        val replacement = mgr.generateKeyPair()
        assertEquals(replacement, state.value)
        assertTrue(mgr.observeHasIdentity().value)
    }

    @Test
    fun `rejected or unwritten private key never announces the requested replacement`() {
        mgr.importKey(validPrivHex)
        val state = mgr.observeActivePublicKey()
        assertThrows(IllegalArgumentException::class.java) { mgr.importKey("00".repeat(32)) }
        storage.failNextPut = true
        assertThrows(SecureStorageException::class.java) { mgr.importKey(otherPrivHex) }
        assertEquals(validPubHex, state.value)
        assertEquals(validPubHex, mgr.getPublicKeyHex())
    }

    @Test
    fun `failed mirror write announces the authoritative private key once and retry is quiet`() = runTest {
        storage.putString("nsec", validPrivHex)
        var failMirror = true
        val failing = object : SecureStorage by storage {
            override fun putString(key: String, value: String) {
                if (key == "npub" && failMirror) throw SecureStorageException("mirror write failed")
                storage.putString(key, value)
            }
        }
        val manager = IdentityManager(context, failing)
        val seen = mutableListOf<String?>()
        val state = manager.observeActivePublicKey()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.toList(seen) }
        assertThrows(SecureStorageException::class.java) { manager.importKey(otherPrivHex) }
        assertEquals(otherPubHex, state.value)
        assertEquals(otherPubHex, manager.getPublicKeyHex())
        failMirror = false
        manager.importKey(otherPrivHex)
        assertEquals(listOf(validPubHex, otherPubHex), seen)
    }

    @Test
    fun `cold unavailable store seeds null then successful read recovers the same observer without looping`() =
        runTest {
            storage.putString("nsec", validPrivHex)
            storage.transientFailure = SecureStorageException("temporarily locked")
            val state = mgr.observeActivePublicKey()
            val seen = mutableListOf<String?>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.toList(seen) }
            assertNull(state.value)
            storage.transientFailure = null
            repeat(4) { assertEquals(validPubHex, mgr.getPublicKeyHex()) }
            assertEquals(listOf(null, validPubHex), seen)
        }

    @Test
    fun `unconfirmed promotion retains pending key and cached and observed active identity`() {
        storage.putString("nsec", validPrivHex)
        storage.putString("nsec_pending", otherPrivHex)
        val lying = object : SecureStorage by storage {
            override fun putString(key: String, value: String) {
                if (key != "nsec") storage.putString(key, value)
            }
        }
        val manager = IdentityManager(context, lying)
        val state = manager.observeActivePublicKey()
        assertThrows(SecureStorageException::class.java) { manager.finishPendingKeyPair(otherPubHex) }
        assertEquals(validPubHex, state.value)
        assertEquals(validPubHex, manager.getPublicKeyHex())
        assertEquals(otherPubHex, manager.getPendingPublicKeyHex())
    }

    // --- getPublicKeyHex: derived from nsec, never trusted from npub ---

    @Test
    fun `getPublicKeyHex is derived from the private key when npub is missing`() {
        storage.putString("nsec", validPrivHex)
        assertEquals(validPubHex, mgr.getPublicKeyHex())
    }

    @Test
    fun `getPublicKeyHex ignores a stale or wrong npub`() {
        storage.putString("nsec", validPrivHex)
        storage.putString("npub", otherPubHex) // mismatched pair, e.g. crash between two writes
        assertEquals(validPubHex, mgr.getPublicKeyHex())
    }

    @Test
    fun `getPublicKeyHex returns empty when there is no identity`() {
        assertEquals("", mgr.getPublicKeyHex())
        storage.putString("npub", otherPubHex) // orphaned npub without a private key is not an identity
        assertEquals("", mgr.getPublicKeyHex())
    }

    @Test
    fun `getPublicKeyHex falls back to stored npub only when derivation fails`() {
        storage.putString("nsec", "not-a-key")
        storage.putString("npub", "legacy-npub")
        assertEquals("legacy-npub", mgr.getPublicKeyHex())
        verify { android.util.Log.w("IdentityManager", any<String>(), any()) }
    }

    @Test
    fun `getPublicKeyHex derives once and then serves the cached value`() {
        val spy = spyk(FakeSecureStorage().also { it.putString("nsec", validPrivHex) })
        val cached = IdentityManager(context, spy)
        repeat(3) { assertEquals(validPubHex, cached.getPublicKeyHex()) }
        verify(exactly = 1) { spy.getString("nsec", null) }
    }

    @Test
    fun `getPublicKeyHex cache is invalidated when the private key changes`() {
        storage.putString("nsec", validPrivHex)
        assertEquals(validPubHex, mgr.getPublicKeyHex())

        mgr.importKey(otherPrivHex)
        assertEquals(otherPubHex, mgr.getPublicKeyHex())

        storage.putString("nsec_pending", validPrivHex)
        mgr.commitPendingKeyPair()
        assertEquals(validPubHex, mgr.getPublicKeyHex())

        val generated = mgr.generateKeyPair()
        assertEquals(generated, mgr.getPublicKeyHex())
        assertEquals(NostrEvent.pubkeyFromPrivkey(storage.getString("nsec", null)!!.hexToBytes()), generated)
    }

    @Test
    fun `getPrivateKeyHex`() {
        storage.putString("nsec", "ccdd")
        assertEquals("ccdd", mgr.getPrivateKeyHex())
    }

    @Test
    fun `getPrivateKeyBytes`() {
        storage.putString("nsec", validPrivHex)
        assertEquals(32, mgr.getPrivateKeyBytes().size)
    }

    @Test
    fun `getPublicKeyBytes`() {
        storage.putString("nsec", validPrivHex)
        assertArrayEquals(validPubHex.hexToBytes(), mgr.getPublicKeyBytes())
    }

    @Test
    fun `generateKeyPair stores a matching pair and preserves pending for coordination`() {
        storage.putString("nsec_pending", otherPrivHex)
        storage.putString("npub_pending", otherPubHex)
        val pub = mgr.generateKeyPair()
        assertEquals(64, pub.length)
        val priv = storage.getString("nsec", null)!!
        assertEquals(64, priv.length)
        assertEquals(NostrEvent.pubkeyFromPrivkey(priv.hexToBytes()), pub)
        assertEquals(pub, storage.getString("npub", null))
        assertEquals(pub, mgr.getPublicKeyHex())
        assertEquals(otherPrivHex, storage.getString("nsec_pending", null))
        assertEquals(otherPubHex, storage.getString("npub_pending", null))
    }

    @Test
    fun `generateKeyPair sets boot identity flag`() {
        val bootPrefs = mockk<SharedPreferences>(relaxed = true)
        val bootEditor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { context.getSharedPreferences("splitfree_boot", Context.MODE_PRIVATE) } returns bootPrefs
        every { bootPrefs.edit() } returns bootEditor
        every { bootEditor.putBoolean(any(), any()) } returns bootEditor
        every { bootEditor.apply() } just Runs
        mgr.generateKeyPair()
        verify { bootEditor.putBoolean("identity_created", true) }
        verify { bootEditor.apply() }
    }

    @Test
    fun `generatePendingKeyPair stores a matching pending pair`() {
        val pub = mgr.generatePendingKeyPair()
        assertEquals(64, pub.length)
        val priv = storage.getString("nsec_pending", null)!!
        assertEquals(64, priv.length)
        assertEquals(NostrEvent.pubkeyFromPrivkey(priv.hexToBytes()), pub)
        assertEquals(pub, storage.getString("npub_pending", null))
        assertEquals(pub, mgr.getPendingPublicKeyHex())
    }

    @Test
    fun `generatePendingKeyPair reuses durable private key even when public mirror was never written`() {
        storage.putString("nsec_pending", otherPrivHex)
        assertEquals(otherPubHex, mgr.generatePendingKeyPair())
        assertEquals(otherPrivHex, storage.getString("nsec_pending", null))
    }

    @Test
    fun `finishPendingKeyPair repairs cleanup after promotion without a pending private key`() {
        storage.putString("nsec", otherPrivHex)
        storage.putString("npub", validPubHex)
        storage.putString("npub_pending", otherPubHex)
        storage.putString("revocation_event_ids", "event")
        storage.putLong("revocation_start", 1)
        mgr.finishPendingKeyPair(otherPubHex)
        mgr = IdentityManager(context, storage)
        mgr.finishPendingKeyPair(otherPubHex)
        assertEquals(otherPubHex, storage.getString("npub", null))
        assertEquals(otherPrivHex, storage.getString("nsec", null))
        assertFalse(mgr.hasPendingKeyPair())
        assertNull(storage.getString("npub_pending", null))
        assertTrue(mgr.getRevocationEventIds().isEmpty())
        assertEquals(0L, mgr.getRevocationStartTime())
    }

    @Test
    fun `promotion invalidates cached public key even when storage throws after active private write`() {
        storage.putString("nsec", validPrivHex)
        storage.putString("nsec_pending", otherPrivHex)
        val failing = object : SecureStorage by storage {
            override fun putString(key: String, value: String) {
                storage.putString(key, value)
                if (key == "nsec") throw SecureStorageException("injected after durable active write")
            }
        }
        val manager = IdentityManager(context, failing)
        assertEquals(validPubHex, manager.getPublicKeyHex())
        val state = manager.observeActivePublicKey()
        assertThrows(SecureStorageException::class.java) { manager.finishPendingKeyPair(otherPubHex) }
        assertEquals(otherPubHex, state.value)
        assertEquals(otherPubHex, manager.getPublicKeyHex())
        manager.finishPendingKeyPair(otherPubHex)
        assertFalse(manager.hasPendingKeyPair())
    }

    @Test
    fun `finishPendingKeyPair cannot promote a replacement different from the journal target`() {
        storage.putString("nsec", validPrivHex)
        storage.putString("nsec_pending", otherPrivHex)
        assertThrows(IllegalStateException::class.java) { mgr.finishPendingKeyPair("cc".repeat(32)) }
        assertEquals(validPrivHex, storage.getString("nsec", null))
        assertEquals(otherPrivHex, storage.getString("nsec_pending", null))
    }

    @Test
    fun `commitPendingKeyPair promotes pending to active`() {
        storage.putString("nsec", validPrivHex)
        storage.putString("npub", validPubHex)
        storage.putString("nsec_pending", otherPrivHex)
        storage.putString("npub_pending", otherPubHex)
        mgr.commitPendingKeyPair()
        assertEquals(otherPrivHex, storage.getString("nsec", null))
        assertEquals(otherPubHex, storage.getString("npub", null))
        assertEquals(otherPubHex, mgr.getPublicKeyHex())
        assertFalse(storage.contains("nsec_pending"))
        assertFalse(storage.contains("npub_pending"))
    }

    @Test
    fun `commitPendingKeyPair works without a stored pending npub`() {
        storage.putString("nsec_pending", otherPrivHex)
        mgr.commitPendingKeyPair()
        assertEquals(otherPrivHex, storage.getString("nsec", null))
        assertEquals(otherPubHex, storage.getString("npub", null))
        assertEquals(otherPubHex, mgr.getPublicKeyHex())
    }

    @Test
    fun `commitPendingKeyPair throws when no pending private`() {
        assertThrows(IllegalStateException::class.java) { mgr.commitPendingKeyPair() }
    }

    @Test
    fun `commitPendingKeyPair throws when the pending private key is unusable and no pending public exists`() {
        storage.putString("nsec_pending", "priv")
        assertThrows(IllegalStateException::class.java) { mgr.commitPendingKeyPair() }
        assertFalse(storage.contains("nsec"))
    }

    @Test
    fun `discardPendingKeyPair removes pending`() {
        storage.putString("nsec_pending", "p")
        storage.putString("npub_pending", "p")
        mgr.discardPendingKeyPair()
        assertFalse(storage.contains("nsec_pending"))
        assertFalse(storage.contains("npub_pending"))
    }

    @Test
    fun `hasPendingKeyPair`() {
        assertFalse(mgr.hasPendingKeyPair())
        storage.putString("nsec_pending", "p")
        assertTrue(mgr.hasPendingKeyPair())
    }

    @Test
    fun `getPendingPublicKeyHex is derived from the pending private key`() {
        assertNull(mgr.getPendingPublicKeyHex())
        storage.putString("nsec_pending", otherPrivHex)
        assertEquals(otherPubHex, mgr.getPendingPublicKeyHex())
        storage.putString("npub_pending", validPubHex) // wrong mirror is ignored
        assertEquals(otherPubHex, mgr.getPendingPublicKeyHex())
    }

    @Test
    fun `getPendingPublicKeyHex falls back to stored npub_pending only when derivation fails`() {
        storage.putString("nsec_pending", "garbage")
        assertNull(mgr.getPendingPublicKeyHex())
        storage.putString("npub_pending", "abc")
        assertEquals("abc", mgr.getPendingPublicKeyHex())
    }

    @Test
    fun `getPendingPrivateKeyBytes returns bytes or null`() {
        assertNull(mgr.getPendingPrivateKeyBytes())
        storage.putString("nsec_pending", validPrivHex)
        assertEquals(32, mgr.getPendingPrivateKeyBytes()!!.size)
    }

    @Test
    fun `exportAsMnemonic returns 24 words`() {
        storage.putString("nsec", validPrivHex)
        val words = mgr.exportAsMnemonic()
        assertEquals(24, words.size)
    }

    @Test
    fun `importKey with hex`() {
        mgr.importKey(validPrivHex)
        assertEquals(validPrivHex, storage.getString("nsec", null))
        assertEquals(validPubHex, storage.getString("npub", null))
        assertEquals(validPubHex, mgr.getPublicKeyHex())
    }

    @Test
    fun `raw importKey preserves pending keypair and tracking for coordinated reconciliation`() {
        storage.putString("nsec", otherPrivHex)
        storage.putString("nsec_pending", otherPrivHex)
        storage.putString("npub_pending", otherPubHex)
        mgr.markRevocationStarted()
        mgr.setRevocationEventIds(listOf("e1", "e2"))

        mgr.importKey(validPrivHex)

        assertEquals(validPrivHex, storage.getString("nsec", null))
        assertTrue(mgr.hasPendingKeyPair())
        assertEquals(otherPrivHex, storage.getString("nsec_pending", null))
        assertEquals(otherPubHex, storage.getString("npub_pending", null))
        assertTrue(mgr.getRevocationStartTime() > 0)
        assertEquals(listOf("e1", "e2"), mgr.getRevocationEventIds())
    }

    @Test
    fun `importKey failure leaves the active key and pending state untouched`() {
        storage.putString("nsec", otherPrivHex)
        storage.putString("npub", otherPubHex)
        storage.putString("nsec_pending", validPrivHex)
        mgr.setRevocationEventIds(listOf("e1"))

        // Wrong length, all-zero (fails secKeyVerify) and non-hex all take the failure path.
        for (bad in listOf("abcd", "00".repeat(32), "zz".repeat(32))) {
            assertThrows(IllegalArgumentException::class.java) { mgr.importKey(bad) }
        }

        assertEquals(otherPrivHex, storage.getString("nsec", null))
        assertEquals(otherPubHex, storage.getString("npub", null))
        assertEquals(otherPubHex, mgr.getPublicKeyHex())
        assertEquals(validPrivHex, storage.getString("nsec_pending", null))
        assertEquals(listOf("e1"), mgr.getRevocationEventIds())
    }

    @Test
    fun `importKey writes the private key before the public key`() {
        val writes = mutableListOf<String>()
        val recording = spyk(FakeSecureStorage())
        every { recording.putString(capture(writes), any()) } answers { callOriginal() }
        IdentityManager(context, recording).importKey(validPrivHex)
        assertEquals(listOf("nsec", "npub"), writes.filter { it == "nsec" || it == "npub" })
    }

    @Test
    fun `importKey sets boot identity flag`() {
        val bootPrefs = mockk<SharedPreferences>(relaxed = true)
        val bootEditor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { context.getSharedPreferences("splitfree_boot", Context.MODE_PRIVATE) } returns bootPrefs
        every { bootPrefs.edit() } returns bootEditor
        every { bootEditor.putBoolean(any(), any()) } returns bootEditor
        every { bootEditor.apply() } just Runs
        mgr.importKey(validPrivHex)
        verify { bootEditor.putBoolean("identity_created", true) }
    }

    @Test
    fun `importKey with 24-word mnemonic`() {
        val mnemonic = Bip39.toMnemonic(validPrivHex.hexToBytes())
        mgr.importKey(mnemonic.joinToString(" "))
        assertEquals(validPrivHex, storage.getString("nsec", null))
        assertEquals(validPubHex, storage.getString("npub", null))
    }

    @Test
    fun `importKey rejects 12-word mnemonic`() {
        val words24 = Bip39.toMnemonic(validPrivHex.hexToBytes())
        val words12 = words24.take(12).joinToString(" ")
        assertThrows(IllegalArgumentException::class.java) { mgr.importKey(words12) }
    }

    @Test
    fun `importKey rejects invalid hex key`() {
        assertThrows(IllegalArgumentException::class.java) {
            mgr.importKey("00".repeat(32))
        }
    }

    @Test
    fun `importKey trims whitespace`() {
        mgr.importKey("  $validPrivHex  ")
        assertEquals(validPrivHex, storage.getString("nsec", null))
    }

    @Test
    fun `setRevocationEventIds stores ids only`() {
        mgr.setRevocationEventIds(listOf("e1", "e2"))
        assertEquals("e1,e2", storage.getString("revocation_event_ids", null))
        assertEquals(listOf("e1", "e2"), mgr.getRevocationEventIds())
        // The start time is owned by markRevocationStarted, not by the event-id write.
        assertEquals(0L, storage.getLong("revocation_start", 0L))
    }

    @Test
    fun `markRevocationStarted records the current epoch seconds`() {
        val before = System.currentTimeMillis() / 1000
        mgr.markRevocationStarted()
        val start = mgr.getRevocationStartTime()
        assertTrue(start >= before && start <= System.currentTimeMillis() / 1000)
    }

    @Test
    fun `commitPendingKeyPair clears revocation tracking`() {
        storage.putString("nsec_pending", otherPrivHex)
        storage.putString("npub_pending", otherPubHex)
        mgr.markRevocationStarted()
        mgr.setRevocationEventIds(listOf("e1"))
        mgr.commitPendingKeyPair()
        assertEquals(0L, mgr.getRevocationStartTime())
        assertEquals(emptyList<String>(), mgr.getRevocationEventIds())
    }

    @Test
    fun `discardPendingKeyPair clears revocation tracking`() {
        storage.putString("nsec_pending", otherPrivHex)
        storage.putString("npub_pending", otherPubHex)
        mgr.markRevocationStarted()
        mgr.setRevocationEventIds(listOf("e1"))
        mgr.discardPendingKeyPair()
        assertEquals(0L, mgr.getRevocationStartTime())
        assertEquals(emptyList<String>(), mgr.getRevocationEventIds())
    }

    @Test
    fun `getRevocationEventIds returns empty when not set`() {
        assertEquals(emptyList<String>(), mgr.getRevocationEventIds())
    }

    @Test
    fun `getRevocationStartTime returns 0 when not set`() {
        assertEquals(0L, mgr.getRevocationStartTime())
    }

    // --- identity state and recovery after Keystore key loss ---

    @Test
    fun `identityState is ABSENT on a fresh store and READY once a key is stored`() {
        assertEquals(IdentityState.ABSENT, mgr.identityState())
        storage.putString("nsec", validPrivHex)
        assertEquals(IdentityState.READY, mgr.identityState())
    }

    @Test
    fun `identityState is RECOVERY_REQUIRED when the wrapping key is lost and UNAVAILABLE on a transient failure`() {
        storage.putString("nsec", validPrivHex)
        storage.putString("npub", validPubHex)

        storage.keyLost = true
        assertEquals(IdentityState.RECOVERY_REQUIRED, mgr.identityState())
        assertFalse(mgr.hasIdentity())

        storage.keyLost = false
        storage.transientFailure = SecureStorageException("daemon busy")
        assertEquals(IdentityState.UNAVAILABLE, mgr.identityState())
        assertFalse(mgr.hasIdentity())
        assertEquals(0, storage.resets)
    }

    @Test
    fun `identityState is RECOVERY_REQUIRED when the stored blob itself is unreadable`() {
        val corrupt = mockk<SecureStorage>()
        every { corrupt.contains("nsec") } returns true
        every { corrupt.getString("nsec", null) } returns null
        assertEquals(IdentityState.RECOVERY_REQUIRED, IdentityManager(context, corrupt).identityState())
    }

    @Test
    fun `a valid phrase repairs a store whose wrapping key was lost and installs the identity`() {
        storage.putString("nsec", otherPrivHex)
        storage.putString("npub", otherPubHex)
        storage.putString("nsec_pending", "stale")
        storage.keyLost = true
        assertEquals(IdentityState.RECOVERY_REQUIRED, mgr.identityState())

        mgr.importKey(Bip39.toMnemonic(validPrivHex.hexToBytes()).joinToString(" "))

        assertEquals(1, storage.resets)
        assertEquals(validPrivHex, storage.getString("nsec", null))
        assertEquals(validPubHex, mgr.getPublicKeyHex())
        assertFalse(storage.contains("nsec_pending"))
        assertEquals(IdentityState.READY, mgr.identityState())
        assertTrue(mgr.hasIdentity())
        assertTrue(mgr.observeHasIdentity().value)
    }

    @Test
    fun `an invalid phrase never touches a store whose wrapping key was lost`() {
        storage.putString("nsec", otherPrivHex)
        storage.keyLost = true

        assertThrows(IllegalArgumentException::class.java) { mgr.importKey("not a key at all") }
        assertThrows(IllegalArgumentException::class.java) { mgr.importKey("00".repeat(32)) }

        assertEquals(0, storage.resets)
        assertTrue(storage.contains("nsec"))
        assertTrue(storage.keyLost)
        assertEquals(IdentityState.RECOVERY_REQUIRED, mgr.identityState())
    }

    @Test
    fun `a transient storage failure during import is reported as such and resets nothing`() {
        storage.putString("nsec", otherPrivHex)
        storage.transientFailure = SecureStorageException("daemon busy")

        val failure = assertThrows(SecureStorageException::class.java) { mgr.importKey(validPrivHex) }

        assertFalse(failure is SecureStorageKeyLostException)
        assertEquals(0, storage.resets)
        storage.transientFailure = null
        // The previous identity is still there once the store answers again.
        assertEquals(otherPrivHex, storage.getString("nsec", null))
        assertEquals(IdentityState.READY, mgr.identityState())
    }

    @Test
    fun `a write that does not read back is reported instead of trusted`() {
        val lying = object : SecureStorage by storage {
            override fun putString(key: String, value: String) {
                // Reports success without persisting the private key.
                if (key != "nsec") storage.putString(key, value)
            }
        }
        val manager = IdentityManager(context, lying)

        assertThrows(SecureStorageException::class.java) { manager.importKey(validPrivHex) }

        assertFalse(storage.contains("nsec"))
        assertEquals("", manager.getPublicKeyHex())
    }

    @Test
    fun `a fresh identity also repairs a store whose wrapping key was lost`() {
        storage.putString("nsec", otherPrivHex)
        storage.keyLost = true

        val pub = mgr.generateKeyPair()

        assertEquals(1, storage.resets)
        assertEquals(pub, mgr.getPublicKeyHex())
        assertEquals(IdentityState.READY, mgr.identityState())
    }

    @Test
    fun `a fresh identity does not replace a key behind a transient failure`() {
        storage.putString("nsec", otherPrivHex)
        storage.transientFailure = SecureStorageException("daemon busy")

        assertThrows(SecureStorageException::class.java) { mgr.generateKeyPair() }

        storage.transientFailure = null
        assertEquals(otherPrivHex, storage.getString("nsec", null))
        assertEquals(0, storage.resets)
    }

    @Test
    fun `a single failed read never lets a fresh identity replace the stored key`() {
        storage.putString("nsec", otherPrivHex)
        storage.putString("npub", otherPubHex)
        var failReads = 1
        val flaky = object : SecureStorage by storage {
            override fun getString(key: String, default: String?): String? {
                if (key == "nsec" && failReads > 0) {
                    failReads--
                    throw SecureStorageException("one decrypt failed")
                }
                return storage.getString(key, default)
            }
        }
        val manager = IdentityManager(context, flaky)

        assertThrows(SecureStorageException::class.java) { manager.generateKeyPair() }

        assertEquals(otherPrivHex, storage.getString("nsec", null))
        assertEquals(otherPubHex, manager.getPublicKeyHex())
        assertEquals(IdentityState.READY, manager.identityState())
    }

    @Test
    fun `a single failed read never lets an import replace the stored key either`() {
        storage.putString("nsec", otherPrivHex)
        var failReads = 1
        val flaky = object : SecureStorage by storage {
            override fun getString(key: String, default: String?): String? {
                if (key == "nsec" && failReads > 0) {
                    failReads--
                    throw SecureStorageException("one decrypt failed")
                }
                return storage.getString(key, default)
            }
        }
        val manager = IdentityManager(context, flaky)

        assertThrows(SecureStorageException::class.java) { manager.importKey(validPrivHex) }

        assertEquals(otherPrivHex, storage.getString("nsec", null))
        assertEquals(0, storage.resets)
    }

    @Test
    fun `staging after wrapping key loss ignores cached owner and repairs only after valid input`() {
        mgr.importKey(validPrivHex)
        assertEquals(validPubHex, mgr.getPublicKeyHex())
        mgr.generatePendingKeyPair()
        storage.keyLost = true
        assertThrows(IllegalArgumentException::class.java) { mgr.stageIdentitySwitch("not a key") }
        assertEquals(0, storage.resets)
        val target = mgr.stageIdentitySwitch(otherPrivHex)
        assertNull(target.oldPubkey)
        assertNull(target.pendingPubkey)
        mgr.commitIdentitySwitch(target)
        assertEquals(otherPubHex, mgr.getPublicKeyHex())
        assertEquals(1, storage.resets)
    }

    @Test
    fun `multiple possibly public successors for one owner are retained and restored by exact target`() {
        mgr.importKey(validPrivHex)
        val first = mgr.generatePendingKeyPair()
        mgr.setRevocationEventIds(listOf("first-event"))
        mgr.archivePendingKeyPair(validPubHex)
        val second = mgr.generatePendingKeyPair()
        mgr.setRevocationEventIds(listOf("second-event"))
        mgr.archivePendingKeyPair(validPubHex)
        assertEquals(first, mgr.getArchivedPendingPublicKeyHex(validPubHex, first))
        assertEquals(second, mgr.getArchivedPendingPublicKeyHex(validPubHex, second))
        assertNull(mgr.getArchivedPendingPublicKeyHex(validPubHex))
        assertThrows(IllegalStateException::class.java) { mgr.restoreArchivedPendingKeyPair(validPubHex) }
        mgr.restoreArchivedPendingKeyPair(validPubHex, first)
        assertEquals(first, mgr.getPendingPublicKeyHex())
        assertEquals(listOf("first-event"), mgr.getRevocationEventIds())
        mgr.archivePendingKeyPair(validPubHex)
        mgr.restoreArchivedPendingKeyPair(validPubHex, second)
        assertEquals(second, mgr.getPendingPublicKeyHex())
        assertEquals(listOf("second-event"), mgr.getRevocationEventIds())
    }

    /** Hides only nsec until it is rewritten; other fake-store values remain readable. */
    private fun corruptActiveKey(): SecureStorage = object : SecureStorage by storage {
        var repaired = false
        override fun getString(key: String, default: String?): String? =
            if (key == "nsec" && !repaired) default else storage.getString(key, default)
        override fun canDecrypt(key: String): Boolean = getString(key, null) != null
        override fun putString(key: String, value: String) {
            storage.putString(key, value)
            if (key == "nsec") repaired = true
        }
    }

    @Test
    fun `recovering the same identity keeps a readable pending successor and its tracking`() {
        storage.putString("nsec", validPrivHex)
        storage.putString("npub", validPubHex)
        val manager = IdentityManager(context, corruptActiveKey())
        val pending = manager.generatePendingKeyPair()
        manager.markRevocationStarted()
        manager.setRevocationEventIds(listOf("maybe-published"))
        assertEquals(IdentityState.RECOVERY_REQUIRED, manager.identityState())
        assertEquals(pending, manager.getPendingPublicKeyHex())

        // The user re-enters the phrase of the identity that is already here.
        manager.importKey(Bip39.toMnemonic(validPrivHex.hexToBytes()).joinToString(" "))

        assertEquals(IdentityState.READY, manager.identityState())
        assertEquals(validPubHex, manager.getPublicKeyHex())
        assertEquals(pending, manager.getPendingPublicKeyHex())
        assertEquals(listOf("maybe-published"), manager.getRevocationEventIds())
        assertTrue(manager.getRevocationStartTime() > 0)
    }

    @Test
    fun `raw import of a different identity preserves a possibly public successor`() {
        storage.putString("nsec", validPrivHex)
        storage.putString("npub", validPubHex)
        val manager = IdentityManager(context, corruptActiveKey())
        val pending = manager.generatePendingKeyPair()
        manager.setRevocationEventIds(listOf("e1"))

        manager.importKey(otherPrivHex)

        assertEquals(otherPubHex, manager.getPublicKeyHex())
        assertEquals(pending, manager.getPendingPublicKeyHex())
        assertEquals(listOf("e1"), manager.getRevocationEventIds())
    }

    @Test
    fun `re-importing the current identity keeps an in-flight replacement`() {
        mgr.importKey(validPrivHex)
        val pending = mgr.generatePendingKeyPair()
        mgr.setRevocationEventIds(listOf("e1"))

        mgr.importKey(validPrivHex)

        assertEquals(pending, mgr.getPendingPublicKeyHex())
        assertEquals(listOf("e1"), mgr.getRevocationEventIds())
    }

    @Test
    fun `a comparison read that fails once must not delete an intact pending identity`() {
        mgr.importKey(validPrivHex)
        val pending = mgr.generatePendingKeyPair()
        mgr.setRevocationEventIds(listOf("possibly-public"))
        var nsecReads = 0
        val flaky = object : SecureStorage by storage {
            override fun getString(key: String, default: String?): String? {
                // Fail the second nsec read, which verifies the newly written key.
                if (key == "nsec" && ++nsecReads == 2) throw SecureStorageException("one decrypt failed")
                return storage.getString(key, default)
            }
        }
        val manager = IdentityManager(context, flaky)

        try {
            manager.importKey(validPrivHex)
        } catch (_: SecureStorageException) {
            // Refusing the import is acceptable; deleting the pending successor is not.
        }

        assertEquals(validPrivHex, storage.getString("nsec", null))
        assertEquals(pending, manager.getPendingPublicKeyHex())
        assertEquals(listOf("possibly-public"), manager.getRevocationEventIds())
    }

    @Test
    fun `an unreadable active identity with no public mirror keeps the pending successor`() {
        mgr.importKey(validPrivHex)
        val pending = mgr.generatePendingKeyPair()
        mgr.setRevocationEventIds(listOf("possibly-public"))
        storage.remove("npub")
        val manager = IdentityManager(context, corruptActiveKey())
        assertEquals(IdentityState.RECOVERY_REQUIRED, manager.identityState())

        try {
            manager.importKey(validPrivHex)
        } catch (_: SecureStorageException) {
            // Refusing the import is acceptable; deleting the pending successor is not.
        }

        assertEquals(pending, manager.getPendingPublicKeyHex())
        assertEquals(listOf("possibly-public"), manager.getRevocationEventIds())
    }

    @Test
    fun `a public-mirror read that fails once during recovery keeps the pending successor`() {
        mgr.importKey(validPrivHex)
        val pending = mgr.generatePendingKeyPair()
        mgr.setRevocationEventIds(listOf("possibly-public"))
        var npubFailures = 1
        val flaky = object : SecureStorage by storage {
            var repaired = false
            override fun getString(key: String, default: String?): String? {
                if (key == "nsec" && !repaired) return default
                if (key == "npub" && npubFailures > 0) {
                    npubFailures--
                    throw SecureStorageException("one decrypt failed")
                }
                return storage.getString(key, default)
            }
            override fun canDecrypt(key: String): Boolean = getString(key, null) != null
            override fun putString(key: String, value: String) {
                storage.putString(key, value)
                if (key == "nsec") repaired = true
            }
        }
        val manager = IdentityManager(context, flaky)
        assertEquals(IdentityState.RECOVERY_REQUIRED, manager.identityState())

        manager.importKey(validPrivHex)

        assertEquals(pending, manager.getPendingPublicKeyHex())
        assertEquals(listOf("possibly-public"), manager.getRevocationEventIds())
        assertEquals(IdentityState.READY, manager.identityState())
        assertEquals(validPubHex, manager.getPublicKeyHex())
    }

    @Test
    fun `interrupted recovery leaves an empty store that the next import completes`() {
        storage.putString("nsec", otherPrivHex)
        storage.keyLost = true
        // Model interruption after reset but before key installation; no process is restarted.
        storage.resetAfterKeyLoss()
        assertEquals(IdentityState.ABSENT, mgr.identityState())

        mgr.importKey(validPrivHex)

        assertEquals(validPrivHex, storage.getString("nsec", null))
        assertEquals(IdentityState.READY, mgr.identityState())
    }
}
