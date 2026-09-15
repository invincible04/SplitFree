package com.splitfree.ui.util

import android.app.Application
import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupIdentity
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class InviteInputTest {
    @Test
    fun `valid invite preserves confirmation details`() {
        val creator = "aa".repeat(32)
        val created = 1_700_000_000L
        val group = Group(
            GroupIdentity.derive(creator, created),
            "Test group",
            "",
            creator,
            created,
            listOf(creator),
            listOf("wss://nos.lol")
        )
        val link = InviteLinkCodec.encode(group, Base64.getEncoder().encodeToString(ByteArray(32)))
        assertEquals(group.id, decodeInviteForConfirmation(link)?.groupId)
        assertEquals(group.name, decodeInviteForConfirmation(link)?.name)
        assertNull(decodeInviteForConfirmation(link.replace("//join?", "//join.evil?")))
        assertNull(decodeInviteForConfirmation(link.replace("//join?", "//join/path?")))
    }

    @Test
    fun `non invite and malformed scanner inputs are rejected without throwing`() {
        for (input in listOf(
            "",
            "ordinary QR text",
            "https://example.com",
            "splitfree:opaque",
            "splitfree://join",
            "splitfree://join?d=%%%",
            "splitfree://join?d="
        )) {
            assertNull(input, decodeInviteForConfirmation(input))
        }
    }

    @Test
    fun `oversized input is rejected before query decoding`() {
        assertNull(decodeInviteForConfirmation("splitfree://join?d=" + "a".repeat(100_000)))
    }
}
