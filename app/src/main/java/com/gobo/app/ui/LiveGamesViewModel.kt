package com.gobo.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gobo.app.net.LiveGame
import com.gobo.app.net.OgsSocket
import com.gobo.app.net.buildGameListQuery
import com.gobo.app.net.parseGameList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface LiveGamesUiState {
    data object Loading : LiveGamesUiState
    data class Ready(val games: List<LiveGame>) : LiveGamesUiState
    data class Error(val message: String) : LiveGamesUiState
}

/**
 * Backs the spectate list: opens its own socket, authenticates, and runs `gamelist/query` to fetch
 * the current public live games. The query re-runs on every (re)connection so the list survives a
 * dropped socket; [refresh] re-queries on demand.
 */
class LiveGamesViewModel(
    private val socket: OgsSocket,
    private val userJwt: String,
) : ViewModel() {

    private val _state = MutableStateFlow<LiveGamesUiState>(LiveGamesUiState.Loading)
    val state = _state.asStateFlow()

    init {
        socket.connect {
            socket.authenticate(userJwt)
            query()
        }
        // Guard a never-opening socket (no network) so the screen doesn't spin forever.
        viewModelScope.launch {
            delay(12_000)
            if (_state.value is LiveGamesUiState.Loading) {
                _state.value = LiveGamesUiState.Error("Couldn't reach the server. Tap Refresh to retry.")
            }
        }
    }

    /** Re-query the live-games list on the open socket. */
    fun refresh() = query()

    private fun query() {
        viewModelScope.launch {
            val resp = socket.request("gamelist/query", buildGameListQuery())
            _state.value = if (resp != null) {
                LiveGamesUiState.Ready(parseGameList(resp))
            } else {
                LiveGamesUiState.Error("Couldn't load live games. Tap Refresh to retry.")
            }
        }
    }

    /** Close the query socket. Called explicitly from the UI on leaving the screen (created via
     *  `remember`, so `onCleared` won't fire) as well as from [onCleared]. */
    fun close() = socket.close()

    override fun onCleared() = close()
}
