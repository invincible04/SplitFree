package com.splitfree.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitfree.domain.usecase.CreateGroupUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CreateGroupViewModel @Inject constructor(
    private val createGroup: CreateGroupUseCase
) : ViewModel() {
    fun createGroup(name: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val group = createGroup(name)
            onCreated(group.id)
        }
    }
}