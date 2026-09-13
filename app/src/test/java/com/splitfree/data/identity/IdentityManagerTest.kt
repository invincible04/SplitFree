package com.splitfree.data.identity

import android.content.Context
import android.content.SharedPreferences
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.nip.Bip39
import com.splitfree.domain.repository.SecureStorage
import com.splitfree.domain.repository.SecureStorageException
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
    fun `generateKeyPair stores a matching pair and clears pending`() {
        storage.putString("nsec_pending", otherPrivHex)
        storage.putString("npub_pending", otherPubHex)
        val pub = mgr.generateKeyPair()
        assertEquals(64, pub.length)
        val priv = storage.getString("nsec", null)!!
        assertEquals(64, priv.length)
        assertEquals(NostrEvent.pubkeyFromPrivkey(priv.hexToBytes()), pub)
        assertEquals(pub, storage.getString("npub", null))
        assertEquals(pub, mgr.getPublicKeyHex())
        assertFalse(storage.contains("nsec_pending"))
        assertFalse(storage.contains("npub_pending"))
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
        assertThrows(SecureStorageException::class.java) { manager.finishPendingKeyPair(otherPubHex) }
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
    fun `importKey clears pending keypair and revocation tracking`() {
        storage.putString("nsec", otherPrivHex)
        storage.putString("nsec_pending", otherPrivHex)
        storage.putString("npub_pending", otherPubHex)
        mgr.markRevocationStarted()
        mgr.setRevocationEventIds(listOf("e1", "e2"))

        mgr.importKey(validPrivHex)

        assertEquals(validPrivHex, storage.getString("nsec", null))
        assertFalse(mgr.hasPendingKeyPair())
        assertFalse(storage.contains("nsec_pending"))
        assertFalse(storage.contains("npub_pending"))
        assertEquals(0L, mgr.getRevocationStartTime())
        assertEquals(emptyList<String>(), mgr.getRevocationEventIds())
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
}
