package com.gobo.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gobo.app.net.GoStream
import com.gobo.app.net.OgsRest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface GoTvUiState {
    data object Loading : GoTvUiState
    data class Ready(val streams: List<GoStream>) : GoTvUiState
    data class Error(val message: String) : GoTvUiState
}

/** Backs the GoTV list of external live Go streams. Pure REST GET — no socket, no analytics. */
class GoTvViewModel(private val rest: OgsRest) : ViewModel() {

    private val _state = MutableStateFlow<GoTvUiState>(GoTvUiState.Loading)
    val state = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = GoTvUiState.Loading
            rest.fetchGoStreams()
                .onSuccess { _state.value = GoTvUiState.Ready(it) }
                .onFailure {
                    _state.value = GoTvUiState.Error(it.message ?: "Failed to load streams")
                }
        }
    }
}
