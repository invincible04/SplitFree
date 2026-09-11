package com.splitfree.domain.crypto

import com.splitfree.domain.crypto.nip.Nip44
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.SettingsContract
import com.splitfree.domain.util.hexToBytes
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GiftWrapServiceTest {
    private val identity = mockk<IdentityContract>()
    private val userPreferences = mockk<SettingsContract>(relaxed = true)
    private lateinit var service: GiftWrapService

    private val privKey = "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a".hexToBytes()
    private val pubHex = NostrEvent.pubkeyFromPrivkey(privKey)
    private val recipientPriv = "a".repeat(63) + "1"
    private val recipientPub = NostrEvent.pubkeyFromPrivkey(recipientPriv.hexToBytes())

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any(), any()) } returns 0
        every { identity.getPrivateKeyBytes() } returns privKey.copyOf()

        service = GiftWrapService(identity, userPreferences)
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `enabled getter delegates to userPreferences`() {
        every { userPreferences.giftWrapEnabled } returns true
        assertTrue(service.enabled)
        every { userPreferences.giftWrapEnabled } returns false
        assertFalse(service.enabled)
    }

    @Test
    fun `setEnabled writes to userPreferences`() {
        service.setEnabled(true)
        verify { userPreferences.giftWrapEnabled = true }
    }

    @Test
    fun `wrapIfEnabled returns original when disabled`() {
        every { userPreferences.giftWrapEnabled } returns false
        val event =
            NostrEvent(
                id = "id1",
                pubkey = pubHex,
                createdAt = 1,
                kind = 9735,
                tags = emptyList(),
                content = "hi",
                sig = "sig"
            )
        val result = service.wrapIfEnabled(event, recipientPub)
        assertSame(event, result)
    }

    @Test
    fun `wrapIfEnabled wraps when enabled`() {
        every { userPreferences.giftWrapEnabled } returns true
        val event =
            NostrEvent(
                id = "id1",
                pubkey = pubHex,
                createdAt = 1,
                kind = 9735,
                tags = emptyList(),
                content = "hi",
                sig = "sig"
            )
        val wrapped = service.wrapIfEnabled(event, recipientPub)
        assertEquals(1059, wrapped.kind)
        assertNotEquals("hi", wrapped.content)
    }

    @Test
    fun `tryUnwrap returns null for non-1059 kind`() {
        val event =
            NostrEvent(
                id = "id1",
                pubkey = pubHex,
                createdAt = 1,
                kind = 1,
                tags = emptyList(),
                content = "hi",
                sig = "sig"
            )
        assertNull(service.tryUnwrap(event))
    }

    @Test
    fun `tryUnwrap unwraps valid gift wrap`() {
        every { userPreferences.giftWrapEnabled } returns true
        val inner =
            NostrEvent(
                id = "id1",
                pubkey = pubHex,
                createdAt = 1,
                kind = 9735,
                tags = emptyList(),
                content = "hello",
                sig = "sig"
            )
        val wrapped = service.wrapIfEnabled(inner, recipientPub)

        every { identity.getPrivateKeyBytes() } returns recipientPriv.hexToBytes()
        val result = service.tryUnwrap(wrapped)
        assertNotNull(result)
        assertEquals("hello", result!!.rumor.content)
        assertEquals(pubHex, result.senderPubkey)
    }

    @Test
    fun `tryUnwrap exposes the seal signature of the sender`() {
        every { userPreferences.giftWrapEnabled } returns true
        val inner =
            NostrEvent(
                id = "id1",
                pubkey = pubHex,
                createdAt = 1,
                kind = 9735,
                tags = emptyList(),
                content = "hello",
                sig = "sig"
            )
        val wrapped = service.wrapIfEnabled(inner, recipientPub)

        // Peel the outer layer by hand to read the seal the sender actually signed.
        val recipientKey = recipientPriv.hexToBytes()
        val wrapConvKey = Nip44.getConversationKey(recipientKey, wrapped.pubkey.hexToBytes())
        val seal = NostrEvent.fromJson(Nip44.decrypt(wrapped.content, wrapConvKey))!!
        assertEquals(NostrKind.SEAL, seal.kind)
        assertTrue(seal.verify())

        every { identity.getPrivateKeyBytes() } returns recipientPriv.hexToBytes()
        val result = service.tryUnwrap(wrapped)!!
        assertEquals(seal.sig, result.sealSig)
        assertTrue(result.sealSig.isNotEmpty())
        // The rumor stays unsigned; the seal signature is the only proof of authorship.
        assertEquals("", result.rumor.sig)
    }
}
