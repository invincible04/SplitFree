package com.splitfree.ui.viewmodels

import androidx.lifecycle.ViewModel
import com.splitfree.domain.crypto.GiftWrapService
import com.splitfree.domain.crypto.IdentityManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val identity: IdentityManager,
    private val giftWrap: GiftWrapService
) : ViewModel() {
    val nsec: String get() = if (identity.hasIdentity()) identity.getNsec() else ""
    val npub: String get() = if (identity.hasIdentity()) identity.getNpub() else ""

    var giftWrapEnabled: Boolean
        get() = giftWrap.enabled
        set(value) { giftWrap.enabled = value }
}
