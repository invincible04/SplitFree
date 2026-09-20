package com.splitfree.domain.usecase.group

import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.model.group.CreatorTransition
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupIdentity
import com.splitfree.domain.model.group.GroupProjection
import com.splitfree.domain.model.group.GroupProjectionReducer
import com.splitfree.domain.model.group.KeyRevocation
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CreatorInviteProofTest {
    private val keys = (1..7).map { n -> ByteArray(32) { n.toByte() } }
    private val pubs = keys.map(NostrEvent::pubkeyFromPrivkey)
    private val at = 1700000000L
    private val id = GroupIdentity.derive(pubs[0], at)
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { 8 })
    private fun proof(old: Int, new: Int, time: Long = at + old + 1): CreatorTransition {
        val payload = KeyRevocation(
            pubs[old],
            pubs[new],
            successorProof =
            KeyRevocation.proveSuccessor(id, pubs[old], pubs[new], keys[new])
        )
        val event = NostrEvent(
            pubkey = pubs[old],
            createdAt = time,
            kind = 30078,
            tags = listOf(listOf("g", id), listOf("t", "key_revocation")),
            content = "opaque"
        ).sign(keys[old])
        return CreatorTransition.sign(id, event, 0, payload, keys[old])
    }
    private fun group(proofs: List<CreatorTransition>, current: String) = Group(
        id,
        "Trip",
        createdBy = current,
        createdAt = at,
        members = listOf(current),
        relays = listOf("wss://nos.lol"),
        originalCreator = pubs[0],
        creatorTransitions = proofs
    )
    private fun rejected(block: () -> Unit) {
        assertTrue(runCatching(block).exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun roundTripPreservesRootAndFullProof() {
        val p = proof(0, 1)
        val link = InviteLinkCodec.encode(group(listOf(p), pubs[1]), key)
        assertTrue(link.substringAfter("d=").length <= 2048)
        val decoded = InviteLinkCodec.decode(link)
        assertEquals(pubs[0], decoded.creatorPubkey)
        assertEquals(listOf(p), decoded.creatorTransitions)
    }

    @Test fun admissionTimeBoundsDoNotChangeCryptographicValidity() {
        val now = at + 100
        for ((time, accepted) in listOf(now + 3600 to true, now + 3601 to false, Long.MAX_VALUE to false)) {
            val certificate = proof(0, 1, time)
            assertTrue(certificate.verify(id))
            assertEquals(accepted, certificate.hasAdmissibleTimestamp(now))
        }
        assertTrue(proof(0, 1, Long.MAX_VALUE).hasAdmissibleTimestamp(Long.MAX_VALUE))
        assertFalse(proof(0, 1).hasAdmissibleTimestamp(Long.MIN_VALUE))
    }

    @Test fun signedFutureInviteIsRejectedByDecoder() {
        for (time in listOf(System.currentTimeMillis() / 1000 + 7200, Long.MAX_VALUE)) {
            val certificate = proof(0, 1, time)
            assertTrue(certificate.verify(id))
            // Encoding validates certificate signatures; decoding also enforces the admission-time bound.
            val link = InviteLinkCodec.encode(group(listOf(certificate), pubs[1]), key)
            rejected { InviteLinkCodec.decode(link) }
        }
    }

    @Test fun retainedProjectionKeepsCertificatesAfterAdmissionClockRollsBack() {
        val certificate = proof(0, 1, at + 7200)
        assertTrue(certificate.hasAdmissibleTimestamp(at + 7200))
        val state = GroupProjection(
            checkpoint = group(emptyList(), pubs[0]),
            creatorTransitions = listOf(certificate),
            facts = listOf(certificate.fact())
        )
        val stored = kotlinx.serialization.json.Json.encodeToString(state)
        val reopened = kotlinx.serialization.json.Json.decodeFromString<GroupProjection>(stored)
        assertFalse(certificate.hasAdmissibleTimestamp(at))
        assertEquals(listOf(certificate), reopened.verifiedCreatorTransitions())
        val current = GroupProjectionReducer.reduce(reopened).group
        assertEquals(pubs[1], current.createdBy)
        assertEquals(listOf(certificate), current.creatorTransitions)
    }

    @Test fun everySignedFieldAndGroupBindingRejectsTampering() {
        val p = proof(0, 1)
        val changes = listOf(
            p.copy(eventId = "00".repeat(32)),
            p.copy(timestamp = p.timestamp + 1),
            p.copy(epoch = 1),
            p.copy(oldPubkey = pubs[2]),
            p.copy(newPubkey = pubs[2]),
            p.copy(successorProof = "00".repeat(64)),
            p.copy(signature = "00".repeat(64))
        )
        assertTrue(p.verify(id))
        assertFalse(p.verify(GroupIdentity.derive(pubs[0], at + 1)))
        changes.forEach {
            assertFalse(it.verify(id))
            rejected { InviteLinkCodec.encode(group(listOf(it), pubs[1]), key) }
        }
        val bytes = Base64.getUrlDecoder().decode(
            InviteLinkCodec.encode(group(listOf(p), pubs[1]), key).substringAfter("d=")
        )
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        rejected {
            InviteLinkCodec.decode(
                "splitfree://join?d=" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            )
        }
    }

    @Test fun chainForksUseCanonicalEarliestWinnerNotListOrder() {
        val losing = proof(0, 1, at + 20)
        val winning = proof(0, 2, at + 10)
        val follow = proof(2, 3, at + 30)
        for (proofs in listOf(listOf(losing, winning, follow), listOf(follow, winning, losing))) {
            val decoded = InviteLinkCodec.decode(InviteLinkCodec.encode(group(proofs, pubs[3]), key))
            assertEquals(proofs, decoded.creatorTransitions)
            rejected { InviteLinkCodec.encode(group(proofs, pubs[1]), key) }
        }
    }

    @Test fun missingDisconnectedCyclicAndExcessProofsNeverFallBackToRoot() {
        rejected { InviteLinkCodec.encode(group(emptyList(), pubs[1]), key) }
        rejected { InviteLinkCodec.encode(group(listOf(proof(2, 3)), pubs[3]), key) }
        rejected { InviteLinkCodec.encode(group(listOf(proof(0, 1), proof(1, 0)), pubs[0]), key) }
        rejected { InviteLinkCodec.encode(group((0..4).map { proof(it, it + 1) }, pubs[5]), key) }
        val four = (0..3).map { proof(it, it + 1) }
        val maximal = group(four, pubs[4]).copy(name = "n".repeat(100), relays = listOf("wss://" + "r".repeat(248)))
        val link = InviteLinkCodec.encode(maximal, key)
        assertTrue(link.substringAfter("d=").length <= 2048)
        assertEquals(four, InviteLinkCodec.decode(link).creatorTransitions)
    }

    @Test
    fun maximumCompactInviteIsQrEncodableAndDecodable() {
        val proofs = (0..3).map { proof(it, it + 1) }
        val maximal = group(proofs, pubs[4]).copy(
            name = "n".repeat(100),
            relays = listOf("wss://" + "r".repeat(248))
        )
        val link = InviteLinkCodec.encode(maximal, key)
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
            link,
            com.google.zxing.BarcodeFormat.QR_CODE,
            1024,
            1024
        )
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) 0xff000000.toInt() else 0xffffffff.toInt()
        }
        val source = com.google.zxing.RGBLuminanceSource(matrix.width, matrix.height, pixels)
        // Production scanning accepts QR only. A dense random matrix can fool a 1D barcode reader.
        val decoded = com.google.zxing.qrcode.QRCodeReader().decode(
            com.google.zxing.BinaryBitmap(
                com.google.zxing.common.HybridBinarizer(source)
            )
        )
        assertEquals(com.google.zxing.BarcodeFormat.QR_CODE, decoded.barcodeFormat)
        assertEquals(link, decoded.text)
        assertEquals(proofs, InviteLinkCodec.decode(decoded.text).creatorTransitions)
    }

    @Test
    fun boundedCertificatesAndFullRosterFitMetadataAdmissionBudget() {
        val proof = proof(0, 1)
        val members = (1..50).map { it.toString(16).padStart(64, '0') }
        val meta = com.splitfree.domain.model.group.GroupMeta(
            name = "n".repeat(100),
            createdBy = pubs[1],
            createdAt = at,
            members = members,
            relays = listOf("wss://" + "r".repeat(248)),
            memberNames = members.associateWith { "\u0001".repeat(50) },
            originalCreator = pubs[0],
            creatorTransitions = List(CreatorTransition.MAX_TRANSITIONS) { proof }
        )
        val payload = kotlinx.serialization.json.Json.encodeToString(meta)
        assertTrue(payload.toByteArray(Charsets.UTF_8).size <= 65536)
        assertTrue(com.splitfree.domain.validation.EventValidator().isContentSafe(payload))
    }
}
