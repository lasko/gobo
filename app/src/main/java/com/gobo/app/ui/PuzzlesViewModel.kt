package com.gobo.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gobo.app.net.OgsRest
import com.gobo.app.net.PuzzleCollection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PuzzlesUiState {
    data object Loading : PuzzlesUiState
    data class Ready(val collections: List<PuzzleCollection>) : PuzzlesUiState
    data class Error(val message: String) : PuzzlesUiState
}

/** Backs the puzzle-collection browse list. Pure REST GETs — no socket, no analytics. */
class PuzzlesViewModel(private val rest: OgsRest) : ViewModel() {

    private val _state = MutableStateFlow<PuzzlesUiState>(PuzzlesUiState.Loading)
    val state = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = PuzzlesUiState.Loading
            rest.fetchPuzzleCollections()
                .onSuccess { _state.value = PuzzlesUiState.Ready(it) }
                .onFailure {
                    _state.value = PuzzlesUiState.Error(it.message ?: "Failed to load puzzles")
                }
        }
    }
}
