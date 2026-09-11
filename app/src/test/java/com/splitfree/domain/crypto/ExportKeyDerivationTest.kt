package com.splitfree.domain.crypto

import com.splitfree.domain.crypto.nip.Nip44
import com.splitfree.domain.util.hexToBytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportKeyDerivationTest {
    private val privA = "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a".hexToBytes()
    private val privB = ByteArray(32) { 2 }

    @Test
    fun `derived key is 32 bytes`() {
        assertEquals(32, ExportKeyDerivation.MAC_KEY_LENGTH)
        assertEquals(32, ExportKeyDerivation.deriveExportMacKey(privA).size)
    }

    @Test
    fun `derivation is deterministic`() {
        assertArrayEquals(ExportKeyDerivation.deriveExportMacKey(privA), ExportKeyDerivation.deriveExportMacKey(privA))
    }

    @Test
    fun `different private keys give different MAC keys`() {
        assertFalse(
            ExportKeyDerivation.deriveExportMacKey(privA).contentEquals(ExportKeyDerivation.deriveExportMacKey(privB))
        )
    }

    @Test
    fun `derived key differs from the raw private key`() {
        assertFalse(ExportKeyDerivation.deriveExportMacKey(privA).contentEquals(privA))
        assertFalse(ExportKeyDerivation.deriveExportMacKey(privB).contentEquals(privB))
    }

    @Test
    fun `derivation is HKDF-SHA256 with the documented salt and info`() {
        val prk = Nip44.hkdfExtract("splitfree-export-v2".toByteArray(), privA)
        val expected = Nip44.hkdfExpand(prk, "mac".toByteArray(), 32)
        assertArrayEquals(expected, ExportKeyDerivation.deriveExportMacKey(privA))
    }

    @Test
    fun `derivation is domain-separated from the NIP-44 conversation key with self`() {
        val pub = NostrEvent.pubkeyFromPrivkey(privA).hexToBytes()
        val convKey = Nip44.getConversationKey(privA, pub)
        assertFalse(ExportKeyDerivation.deriveExportMacKey(privA).contentEquals(convKey))
    }

    @Test
    fun `input private key is left intact for the caller to zero`() {
        val copy = privA.copyOf()
        ExportKeyDerivation.deriveExportMacKey(copy)
        assertArrayEquals(privA, copy)
        assertTrue(copy.any { it != 0.toByte() })
    }

    @Test
    fun `rejects a private key that is not 32 bytes`() {
        assertThrows(IllegalArgumentException::class.java) { ExportKeyDerivation.deriveExportMacKey(ByteArray(31)) }
        assertThrows(IllegalArgumentException::class.java) { ExportKeyDerivation.deriveExportMacKey(ByteArray(33)) }
    }
}
