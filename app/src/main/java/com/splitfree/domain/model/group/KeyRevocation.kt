package com.splitfree.domain.model.group

import kotlinx.serialization.Serializable

/**
 * Deserialized content of a `key_revocation` event — announces that a member has rotated their identity.
 */
@Serializable
data class KeyRevocation(val oldPubkey: String, val newPubkey: String = "", val reason: String = "")
