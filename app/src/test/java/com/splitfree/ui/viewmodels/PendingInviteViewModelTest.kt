package com.splitfree.ui.viewmodels

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupIdentity
import com.splitfree.domain.util.RelayDefaults
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PendingInviteViewModelTest {
    private val creator = "ab".repeat(32)
    private val group = Group(
        GroupIdentity.derive(creator, 1),
        "Pending invitation",
        createdBy = creator,
        createdAt = 1,
        members = listOf(creator),
        relays = RelayDefaults.DEFAULT_RELAYS.take(1)
    )
    private val link = InviteLinkCodec.encode(group, Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))

    @Test
    fun `saved process state retains unanswered or interrupted invitation without auto joining`() {
        val handle = SavedStateHandle()
        val original = PendingInviteViewModel(handle)
        assertTrue(original.offer(link))
        val restored = PendingInviteViewModel(SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
        assertEquals(link, restored.pendingLink.value)
        assertEquals(link, original.pendingLink.value)
        restored.joined(group.id)
        assertNull(restored.pendingLink.value)
    }

    @Test
    fun `invalid input cannot overwrite pending invite and a different completion cannot consume it`() {
        val model = PendingInviteViewModel(SavedStateHandle())
        model.offer(link)
        assertFalse(model.offer("splitfree://join?d=broken"))
        model.joined("other-group")
        assertEquals(link, model.pendingLink.value)
    }

    @Test
    fun `explicit cancel clears invitation in saved process state`() {
        val handle = SavedStateHandle()
        val model = PendingInviteViewModel(handle)
        model.offer(link)
        model.dismiss()
        assertNull(
            PendingInviteViewModel(
                SavedStateHandle(
                    handle.keys().associateWith {
                        handle.get<Any?>(it)
                    }
                )
            ).pendingLink.value
        )
    }
}
