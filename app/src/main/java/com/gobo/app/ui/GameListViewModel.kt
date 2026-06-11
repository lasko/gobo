package com.gobo.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gobo.app.net.GameSummary
import com.gobo.app.net.OgsRest
import com.gobo.app.net.UiConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface GameListUiState {
    data object Loading : GameListUiState
    data class Ready(val games: List<GameSummary>) : GameListUiState
    data class Error(val message: String) : GameListUiState
}

class GameListViewModel(
    private val rest: OgsRest,
    val config: UiConfig,
) : ViewModel() {

    private val _state = MutableStateFlow<GameListUiState>(GameListUiState.Loading)
    val state = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = GameListUiState.Loading
            rest.fetchActiveGames(config.playerId)
                .onSuccess { _state.value = GameListUiState.Ready(it) }
                .onFailure { _state.value = GameListUiState.Error(it.message ?: "Failed to load games") }
        }
    }
}
