package com.gobo.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gobo.app.net.GameSummary
import com.gobo.app.net.OgsRest
import com.gobo.app.net.PendingChallenge
import com.gobo.app.net.UiConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface GameListUiState {
    data object Loading : GameListUiState
    data class Ready(
        val games: List<GameSummary>,
        /** Open challenges the player has sent that nobody has accepted yet. */
        val pending: List<PendingChallenge>,
    ) : GameListUiState
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
            // Active games are the screen's reason to exist, so a failure there is the
            // error state. Pending challenges are supplementary — best-effort, so a hiccup
            // on that endpoint just hides the section rather than breaking My Games.
            rest.fetchActiveGames(config.playerId)
                .onSuccess { games ->
                    val pending = rest.fetchSentChallenges(config.playerId).getOrDefault(emptyList())
                    _state.value = GameListUiState.Ready(games, pending)
                }
                .onFailure { _state.value = GameListUiState.Error(it.message ?: "Failed to load games") }
        }
    }

    /** Withdraw a sent challenge, then refresh so the list reflects the server. */
    fun cancelChallenge(id: Long) {
        viewModelScope.launch {
            rest.cancelChallenge(id)
            load()
        }
    }
}
