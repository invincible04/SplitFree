package com.splitfree.data.identity

import android.content.Context
import android.content.SharedPreferences
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.nip.Bip39
import com.splitfree.domain.repository.SecureStorage
import com.splitfree.domain.util.hexToBytes
import com.splitfree.test.FakeSecureStorage
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IdentityManagerTest {
    private val context = mockk<Context>(relaxed = true)
    private lateinit var storage: FakeSecureStorage
    private lateinit var mgr: IdentityManager

    // A valid secp256k1 private key (known to pass secKeyVerify)
    private val validPrivHex = "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a"
    private val validPubHex = NostrEvent.pubkeyFromPrivkey(validPrivHex.hexToBytes())

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any(), any()) } returns 0

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

    @Test
    fun `getPublicKeyHex`() {
        storage.putString("npub", "aabb")
        assertEquals("aabb", mgr.getPublicKeyHex())
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
        storage.putString("npub", validPubHex)
        assertEquals(32, mgr.getPublicKeyBytes().size)
    }

    @Test
    fun `generateKeyPair stores keys and clears pending`() {
        val (priv, pub) = mgr.generateKeyPair()
        assertEquals(64, priv.length)
        assertEquals(64, pub.length)
        assertEquals(priv, storage.getString("nsec", null))
        assertEquals(pub, storage.getString("npub", null))
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
    fun `generatePendingKeyPair stores pending keys`() {
        val (priv, pub) = mgr.generatePendingKeyPair()
        assertEquals(64, priv.length)
        assertEquals(64, pub.length)
        assertEquals(priv, storage.getString("nsec_pending", null))
        assertEquals(pub, storage.getString("npub_pending", null))
    }

    @Test
    fun `commitPendingKeyPair promotes pending to active`() {
        storage.putString("nsec_pending", "pendpriv")
        storage.putString("npub_pending", "pendpub")
        mgr.commitPendingKeyPair()
        assertEquals("pendpriv", storage.getString("nsec", null))
        assertEquals("pendpub", storage.getString("npub", null))
        assertFalse(storage.contains("nsec_pending"))
        assertFalse(storage.contains("npub_pending"))
    }

    @Test
    fun `commitPendingKeyPair throws when no pending private`() {
        assertThrows(IllegalStateException::class.java) { mgr.commitPendingKeyPair() }
    }

    @Test
    fun `commitPendingKeyPair throws when no pending public`() {
        storage.putString("nsec_pending", "priv")
        assertThrows(IllegalStateException::class.java) { mgr.commitPendingKeyPair() }
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
    fun `getPendingPublicKeyHex`() {
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
        storage.putString("nsec_pending", "pendpriv")
        storage.putString("npub_pending", "pendpub")
        mgr.markRevocationStarted()
        mgr.setRevocationEventIds(listOf("e1"))
        mgr.commitPendingKeyPair()
        assertEquals(0L, mgr.getRevocationStartTime())
        assertEquals(emptyList<String>(), mgr.getRevocationEventIds())
    }

    @Test
    fun `discardPendingKeyPair clears revocation tracking`() {
        storage.putString("nsec_pending", "pendpriv")
        storage.putString("npub_pending", "pendpub")
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
