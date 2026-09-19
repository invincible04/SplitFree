package com.splitfree.data.util

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyPermanentlyInvalidatedException
import com.splitfree.domain.repository.SecureStorageException
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Reset deletes an unusable alias before clearing ciphertext, preserving retryability after either
 * failure. Keystore, cipher and preferences are mocks; device durability is not exercised.
 */
class KeystoreEncryptedStorageTest {
    private val alias = "test-alias"
    private val values = mutableMapOf<String, Any>()
    private val prefs = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>()
    private val context = mockk<Context>()
    private val keyStore = mockk<KeyStore>()
    private val key = mockk<SecretKey>()
    private val cipher = mockk<Cipher>()
    private lateinit var storage: KeystoreEncryptedStorage

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0

        every { context.getSharedPreferences("test", Context.MODE_PRIVATE) } returns prefs
        every { prefs.all } answers { values.toMap() }
        every { prefs.edit() } returns editor
        every { editor.clear() } returns editor
        every { editor.commit() } answers {
            values.clear()
            true
        }
        mockkStatic(Cipher::class)
        every { Cipher.getInstance("AES/GCM/NoPadding") } returns cipher

        storage =
            spyk(KeystoreEncryptedStorage(context, "test", alias, resetOnCorruption = false), recordPrivateCalls = true)
        every { storage["loadKeyStore"]() } returns keyStore
    }

    @After
    fun teardown() = unmockkAll()

    private fun aliasHoldsKey(usable: Boolean) {
        every { keyStore.containsAlias(alias) } returns true
        every { keyStore.getEntry(alias, null) } returns KeyStore.SecretKeyEntry(key)
        if (usable) {
            every { cipher.init(Cipher.ENCRYPT_MODE, key) } returns Unit
        } else {
            every { cipher.init(Cipher.ENCRYPT_MODE, key) } throws KeyPermanentlyInvalidatedException()
        }
    }

    @Test
    fun `a reset that cannot delete the dead alias leaves every value in place and can be retried`() {
        values["nsec"] = "ciphertext"
        aliasHoldsKey(usable = false)
        every { keyStore.deleteEntry(alias) } throws KeyStoreException("keystore busy")

        assertThrows(SecureStorageException::class.java) { storage.resetAfterKeyLoss() }
        assertEquals(mapOf<String, Any>("nsec" to "ciphertext"), values)

        // Once the alias can go, the same reset completes.
        every { keyStore.deleteEntry(alias) } returns Unit
        storage.resetAfterKeyLoss()
        assertTrue(values.isEmpty())
    }

    @Test
    fun `a reset deletes the dead alias before it clears the values`() {
        values["nsec"] = "ciphertext"
        aliasHoldsKey(usable = false)
        val order = mutableListOf<String>()
        every { keyStore.deleteEntry(alias) } answers { order += "alias" }
        every { editor.commit() } answers {
            order += "values"
            values.clear()
            true
        }

        storage.resetAfterKeyLoss()

        assertEquals(listOf("alias", "values"), order)
        assertTrue(values.isEmpty())
    }

    @Test
    fun `a reset refuses while the key still works`() {
        values["nsec"] = "ciphertext"
        aliasHoldsKey(usable = true)

        assertThrows(SecureStorageException::class.java) { storage.resetAfterKeyLoss() }

        assertEquals(1, values.size)
        verify(exactly = 0) { keyStore.deleteEntry(any()) }
    }

    @Test
    fun `an invalidated alias left over an emptied store is still removed by a reset`() {
        // Model legacy clear-before-delete residue: an empty store with an invalidated alias.
        aliasHoldsKey(usable = false)
        every { keyStore.deleteEntry(alias) } returns Unit

        storage.resetAfterKeyLoss()

        verify(exactly = 1) { keyStore.deleteEntry(alias) }
    }

    @Test
    fun `a missing alias with values behind it is a lost key and the values are cleared`() {
        values["nsec"] = "ciphertext"
        every { keyStore.containsAlias(alias) } returns false

        storage.resetAfterKeyLoss()

        assertTrue(values.isEmpty())
        verify(exactly = 0) { keyStore.deleteEntry(any()) }
    }

    @Test
    fun `a reset that cannot commit the clear reports it after the alias is gone`() {
        values["nsec"] = "ciphertext"
        aliasHoldsKey(usable = false)
        every { keyStore.deleteEntry(alias) } returns Unit
        every { editor.commit() } returns false

        assertThrows(SecureStorageException::class.java) { storage.resetAfterKeyLoss() }

        // Model the next attempt after alias deletion succeeded but clearing values failed.
        every { keyStore.containsAlias(alias) } returns false
        every { editor.commit() } answers {
            values.clear()
            true
        }
        storage.resetAfterKeyLoss()
        assertTrue(values.isEmpty())
    }
}
