package com.splitfree.domain.crypto

import android.content.Context
import android.content.SharedPreferences
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GiftWrapServiceTest {
    private val context = mockk<Context>(relaxed = true)
    private val identity = mockk<IdentityManager>()
    private val prefs = mockk<SharedPreferences>(relaxed = true)
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
    private lateinit var service: GiftWrapService

    private val privKey = "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a".hexToBytes()
    private val pubHex = NostrEvent.pubkeyFromPrivkey(privKey)
    private val recipientPriv = "a".repeat(63) + "1"
    private val recipientPub = NostrEvent.pubkeyFromPrivkey(recipientPriv.hexToBytes())

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any(), any()) } returns 0

        every { prefs.edit() } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.apply() } just Runs
        every { identity.getPrivateKeyBytes() } returns privKey.copyOf()

        service = GiftWrapService(context, identity)
        val field = GiftWrapService::class.java.getDeclaredField("prefs\$delegate")
        field.isAccessible = true
        field.set(service, lazy { prefs })
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `enabled getter delegates to prefs`() {
        every { prefs.getBoolean("gift_wrap_enabled", true) } returns true
        assertTrue(service.enabled)
        every { prefs.getBoolean("gift_wrap_enabled", true) } returns false
        assertFalse(service.enabled)
    }

    @Test
    fun `enabled setter writes to prefs`() {
        service.enabled = true
        verify { editor.putBoolean("gift_wrap_enabled", true) }
        verify { editor.apply() }
    }

    @Test
    fun `wrapIfEnabled returns original when disabled`() {
        every { prefs.getBoolean("gift_wrap_enabled", true) } returns false
        val event = NostrEvent(id = "id1", pubkey = pubHex, createdAt = 1, kind = 9735, tags = emptyList(), content = "hi", sig = "sig")
        val result = service.wrapIfEnabled(event, recipientPub)
        assertSame(event, result)
    }

    @Test
    fun `wrapIfEnabled wraps when enabled`() {
        every { prefs.getBoolean("gift_wrap_enabled", true) } returns true
        val event = NostrEvent(id = "id1", pubkey = pubHex, createdAt = 1, kind = 9735, tags = emptyList(), content = "hi", sig = "sig")
        val wrapped = service.wrapIfEnabled(event, recipientPub)
        assertEquals(1059, wrapped.kind)
        assertNotEquals("hi", wrapped.content)
    }

    @Test
    fun `tryUnwrap returns null for non-1059 kind`() {
        val event = NostrEvent(id = "id1", pubkey = pubHex, createdAt = 1, kind = 1, tags = emptyList(), content = "hi", sig = "sig")
        assertNull(service.tryUnwrap(event))
    }

    @Test
    fun `getCustomRelays returns empty when not set`() {
        every { prefs.getString("custom_relays", null) } returns null
        assertTrue(service.getCustomRelays().isEmpty())
    }

    @Test
    fun `getCustomRelays filters non-wss URLs`() {
        every { prefs.getString("custom_relays", null) } returns "wss://good.relay,ws://bad.relay,http://nope"
        val relays = service.getCustomRelays()
        assertEquals(1, relays.size)
        assertEquals("wss://good.relay", relays[0])
    }

    @Test
    fun `setCustomRelays stores only wss URLs`() {
        service.setCustomRelays(listOf("wss://relay.example.com", "http://bad.com"))
        verify { editor.putString("custom_relays", "wss://relay.example.com") }
    }

    @Test
    fun `tryUnwrap unwraps valid gift wrap`() {
        // Create a real gift wrap, then unwrap it
        every { prefs.getBoolean("gift_wrap_enabled", true) } returns true
        val inner = NostrEvent(id = "id1", pubkey = pubHex, createdAt = 1, kind = 9735, tags = emptyList(), content = "hello", sig = "sig")
        val wrapped = service.wrapIfEnabled(inner, recipientPub)

        // Now unwrap as recipient
        every { identity.getPrivateKeyBytes() } returns recipientPriv.hexToBytes()
        val result = service.tryUnwrap(wrapped)
        assertNotNull(result)
        assertEquals("hello", result!!.first.content)
    }
}
