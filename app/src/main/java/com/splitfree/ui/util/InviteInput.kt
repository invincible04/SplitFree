package com.splitfree.ui.util

import androidx.core.net.toUri
import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.invite.InviteParams

/** Validate untrusted QR/clipboard/deep-link input before decoding or allocating its query parts. */
internal fun decodeInviteForConfirmation(link: String): InviteParams? {
    // Encoded payload is capped at 2048 characters by the codec; leave space for the URI envelope.
    if (link.length > 4096) return null
    return try {
        val uri = link.toUri()
        if (uri.scheme != "splitfree" || uri.authority != "join" || !uri.path.isNullOrEmpty()) return null
        InviteLinkCodec.decode(link)
    } catch (_: IllegalArgumentException) {
        null
    }
}
