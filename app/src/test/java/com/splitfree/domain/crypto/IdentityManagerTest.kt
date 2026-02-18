package com.splitfree.domain.crypto

import android.content.Context
import android.content.SharedPreferences
import com.splitfree.domain.crypto.nip.Bip39
import com.splitfree.util.hexToBytes
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
    private val prefs = mockk<SharedPreferences>(relaxed = true)
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
    private lateinit var mgr: IdentityManager

    // A valid secp256k1 private key (known to pass secKeyVerify)
    private val validPrivHex = "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a"
    private val validPubHex = NostrEvent.pubkeyFromPrivkey(validPrivHex.hexToBytes())

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any(), any()) } returns 0

        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.remove(any()) } returns editor
        every { editor.apply() } just Runs

        mgr = IdentityManager(context)
        val field = IdentityManager::class.java.getDeclaredField("prefs\$delegate")
        field.isAccessible = true
        field.set(mgr, lazy { prefs })
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `hasIdentity true`() {
        every { prefs.contains("nsec") } returns true
        assertTrue(mgr.hasIdentity())
    }

    @Test
    fun `hasIdentity false`() {
        every { prefs.contains("nsec") } returns false
        assertFalse(mgr.hasIdentity())
    }

    @Test
    fun `getPublicKeyHex`() {
        every { prefs.getString("npub", "") } returns "aabb"
        assertEquals("aabb", mgr.getPublicKeyHex())
    }

    @Test
    fun `getPrivateKeyHex`() {
        every { prefs.getString("nsec", "") } returns "ccdd"
        assertEquals("ccdd", mgr.getPrivateKeyHex())
    }

    @Test
    fun `getPrivateKeyBytes`() {
        every { prefs.getString("nsec", "") } returns validPrivHex
        assertEquals(32, mgr.getPrivateKeyBytes().size)
    }

    @Test
    fun `getPublicKeyBytes`() {
        every { prefs.getString("npub", "") } returns validPubHex
        assertEquals(32, mgr.getPublicKeyBytes().size)
    }

    @Test
    fun `generateKeyPair stores keys and clears pending`() {
        val (priv, pub) = mgr.generateKeyPair()
        assertEquals(64, priv.length)
        assertEquals(64, pub.length)
        verify { editor.putString("nsec", priv) }
        verify { editor.putString("npub", pub) }
        verify { editor.remove("nsec_pending") }
        verify { editor.remove("npub_pending") }
        verify { editor.apply() }
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
        verify { editor.putString("nsec_pending", priv) }
        verify { editor.putString("npub_pending", pub) }
        verify { editor.apply() }
    }

    @Test
    fun `commitPendingKeyPair promotes pending to active`() {
        every { prefs.getString("nsec_pending", null) } returns "pendpriv"
        every { prefs.getString("npub_pending", null) } returns "pendpub"
        mgr.commitPendingKeyPair()
        verify { editor.putString("nsec", "pendpriv") }
        verify { editor.putString("npub", "pendpub") }
        verify { editor.remove("nsec_pending") }
        verify { editor.remove("npub_pending") }
    }

    @Test
    fun `commitPendingKeyPair throws when no pending private`() {
        every { prefs.getString("nsec_pending", null) } returns null
        assertThrows(IllegalStateException::class.java) { mgr.commitPendingKeyPair() }
    }

    @Test
    fun `commitPendingKeyPair throws when no pending public`() {
        every { prefs.getString("nsec_pending", null) } returns "priv"
        every { prefs.getString("npub_pending", null) } returns null
        assertThrows(IllegalStateException::class.java) { mgr.commitPendingKeyPair() }
    }

    @Test
    fun `discardPendingKeyPair removes pending`() {
        mgr.discardPendingKeyPair()
        verify { editor.remove("nsec_pending") }
        verify { editor.remove("npub_pending") }
    }

    @Test
    fun `hasPendingKeyPair`() {
        every { prefs.contains("nsec_pending") } returns true
        assertTrue(mgr.hasPendingKeyPair())
        every { prefs.contains("nsec_pending") } returns false
        assertFalse(mgr.hasPendingKeyPair())
    }

    @Test
    fun `getPendingPublicKeyHex`() {
        every { prefs.getString("npub_pending", null) } returns "abc"
        assertEquals("abc", mgr.getPendingPublicKeyHex())
        every { prefs.getString("npub_pending", null) } returns null
        assertNull(mgr.getPendingPublicKeyHex())
    }

    @Test
    fun `getPendingPrivateKeyBytes returns bytes or null`() {
        every { prefs.getString("nsec_pending", null) } returns validPrivHex
        assertEquals(32, mgr.getPendingPrivateKeyBytes()!!.size)
        every { prefs.getString("nsec_pending", null) } returns null
        assertNull(mgr.getPendingPrivateKeyBytes())
    }

    @Test
    fun `exportAsMnemonic returns 24 words`() {
        every { prefs.getString("nsec", "") } returns validPrivHex
        val words = mgr.exportAsMnemonic()
        assertEquals(24, words.size)
    }

    @Test
    fun `importKey with hex`() {
        mgr.importKey(validPrivHex)
        verify { editor.putString("nsec", validPrivHex) }
        verify { editor.putString("npub", validPubHex) }
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
        // Generate mnemonic from known key, then import it back
        val mnemonic = Bip39.toMnemonic(validPrivHex.hexToBytes())
        mgr.importKey(mnemonic.joinToString(" "))
        verify { editor.putString("nsec", validPrivHex) }
        verify { editor.putString("npub", validPubHex) }
    }

    @Test
    fun `importKey rejects 12-word mnemonic`() {
        // Build a string that Bip39.isMnemonic recognizes but has only 12 words
        val words24 = Bip39.toMnemonic(validPrivHex.hexToBytes())
        val words12 = words24.take(12).joinToString(" ")
        // isMnemonic checks if all words are in wordlist — 12 valid words will pass
        assertThrows(IllegalArgumentException::class.java) { mgr.importKey(words12) }
    }

    @Test
    fun `importKey rejects invalid hex key`() {
        // All zeros is not a valid secp256k1 key
        assertThrows(IllegalArgumentException::class.java) {
            mgr.importKey("00".repeat(32))
        }
    }

    @Test
    fun `importKey trims whitespace`() {
        mgr.importKey("  $validPrivHex  ")
        verify { editor.putString("nsec", validPrivHex) }
    }
}
