package com.gobo.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gobo.app.net.Bot
import com.gobo.app.net.ChallengeResult
import com.gobo.app.net.ChallengeSpec
import com.gobo.app.net.OgsRest
import com.gobo.app.net.OgsSocket
import com.gobo.app.net.UiConfig
import com.gobo.app.net.parseActiveBots
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface BotsState {
    data object Loading : BotsState
    data class Ready(val bots: List<Bot>) : BotsState
    data class Error(val message: String) : BotsState
}

sealed interface SubmitState {
    data object Idle : SubmitState
    data object Submitting : SubmitState
    data class Success(val result: ChallengeResult) : SubmitState
    data class Error(val message: String) : SubmitState
}

class NewGameViewModel(
    private val rest: OgsRest,
    val config: UiConfig,
) : ViewModel() {

    private val _bots = MutableStateFlow<BotsState>(BotsState.Loading)
    val bots = _bots.asStateFlow()

    private val _submit = MutableStateFlow<SubmitState>(SubmitState.Idle)
    val submit = _submit.asStateFlow()

    init { loadBots() }

    /** Connect briefly to the realtime socket and read the one-shot active-bots broadcast. */
    fun loadBots() {
        _bots.value = BotsState.Loading
        viewModelScope.launch {
            val socket = OgsSocket()
            try {
                // Subscribe before connecting so we don't miss the broadcast that
                // arrives immediately on open.
                val event = async {
                    withTimeoutOrNull(8_000) {
                        socket.events.first { it.first == "active-bots" }.second
                    }
                }
                socket.connect { /* no auth needed; bots broadcast is public */ }
                val data = event.await()
                _bots.value = if (data != null) {
                    BotsState.Ready(parseActiveBots(data))
                } else {
                    BotsState.Error("Timed out finding online bots")
                }
            } catch (e: Exception) {
                _bots.value = BotsState.Error(e.message ?: "Failed to load bots")
            } finally {
                socket.close()
            }
        }
    }

    fun submit(spec: ChallengeSpec) {
        _submit.value = SubmitState.Submitting
        viewModelScope.launch {
            rest.createChallenge(spec)
                .onSuccess { _submit.value = SubmitState.Success(it) }
                .onFailure { _submit.value = SubmitState.Error(it.message ?: "Challenge failed") }
        }
    }

    fun resetSubmit() { _submit.value = SubmitState.Idle }
}
