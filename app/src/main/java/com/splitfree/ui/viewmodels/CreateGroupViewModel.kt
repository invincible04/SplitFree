package com.splitfree.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitfree.domain.usecase.CreateGroupUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CreateGroupViewModel
    @Inject
    constructor(
        private val createGroup: CreateGroupUseCase,
    ) : ViewModel() {
        private val _error = MutableStateFlow<String?>(null)
        val error: StateFlow<String?> = _error.asStateFlow()

        fun createGroup(
            name: String,
            onCreated: (String) -> Unit,
        ) {
            viewModelScope.launch {
                try {
                    val group = createGroup(name)
                    onCreated(group.id)
                } catch (e: Exception) {
                    _error.value = e.message ?: "Failed to create group"
                }
            }
        }
    }
