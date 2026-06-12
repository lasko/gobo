package com.gobo.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gobo.app.board.BoardState
import com.gobo.app.board.MoveLegality
import com.gobo.app.board.OgsCoord
import com.gobo.app.board.Stone
import com.gobo.app.board.replayMoves
import com.gobo.app.net.ChatMessage
import com.gobo.app.net.OgsRest
import com.gobo.app.net.OgsSocket
import com.gobo.app.net.parseGameChat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.coroutines.delay

/**
 * Lifecycle of an open game screen. We start in [Connecting] and only reveal the
 * board once the server confirms the game with a gamedata snapshot ([Playing]) —
 * bot challenges are accepted optimistically over REST but may still be declined,
 * so we never show a fabricated board before the game truly exists.
 */
sealed interface GamePhase {
    data object Connecting : GamePhase
    data object Playing : GamePhase

    /** Both players passed; the game is in OGS' stone-removal/scoring phase. */
    data object Scoring : GamePhase

    /**
     * Terminal state. [showBoard] is true for a game that actually started and then
     * finished (we keep the final position on screen); false when the game never
     * began (challenge declined, connect failed, timed out) so there is no board.
     */
    data class Over(val message: String, val showBoard: Boolean) : GamePhase
}

class GameViewModel(
    private val rest: OgsRest,
    private val socket: OgsSocket,
    private val myPlayerId: Int = 0,
) : ViewModel() {

    private val _board = MutableStateFlow(BoardState(19))
    val board = _board.asStateFlow()

    private val _phase = MutableStateFlow<GamePhase>(GamePhase.Connecting)
    val phase = _phase.asStateFlow()

    /** black username to white username */
    private val _playerNames = MutableStateFlow("" to "")
    val playerNames = _playerNames.asStateFlow()

    private val _myColor = MutableStateFlow<Stone?>(null)
    val myColor = _myColor.asStateFlow()

    /** True when it's the local player's move (only meaningful while [GamePhase.Playing]). */
    private val _myTurn = MutableStateFlow(false)
    val myTurn = _myTurn.asStateFlow()

    /** (x, y) of the most recent stone, for the last-move marker; null after a pass. */
    private val _lastMove = MutableStateFlow<Pair<Int, Int>?>(null)
    val lastMove = _lastMove.asStateFlow()

    /** Stones captured (by black, by white) — i.e. each player's prisoner count. */
    private val _captures = MutableStateFlow(0 to 0)
    val captures = _captures.asStateFlow()

    /** In-game chat, oldest first. Stays empty unless chat was opted in for this game. */
    private val _chat = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chat = _chat.asStateFlow()

    private var gameId: Long = 0
    private var nextColor = Stone.BLACK
    private var blackId = 0
    private var whiteId = 0
    /** Point forbidden to the side now to move by simple ko, or null. Drives local KO flashes. */
    private var koPoint: Pair<Int, Int>? = null
    /** Dead-stone set proposed during scoring, in OGS encoding; echoed back on accept. */
    private var proposedRemoval = ""

    /**
     * @param chatEnabled when true, request chat on connect so `game/<id>/chat` events
     *   arrive and populate [chat]. Off by default keeps the connect minimal (no chat).
     */
    fun start(gameId: Long, chatEnabled: Boolean = false) {
        this.gameId = gameId
        viewModelScope.launch {
            val cfg = rest.fetchUiConfig().getOrElse {
                end("Couldn't connect: ${it.message}", showBoard = false)
                return@launch
            }
            socket.connect {
                socket.authenticate(cfg.userJwt)
                socket.gameConnect(gameId, playerId = cfg.playerId, chat = chatEnabled)
            }
            launch { collectEvents() }
            launch { keepAlive() }
            launch { startTimeout() }
        }
    }

    /** Guard against a bot that neither accepts nor explicitly declines in time. */
    private suspend fun startTimeout() {
        delay(25_000)
        if (_phase.value is GamePhase.Connecting) {
            end("The game didn't start. The bot may be busy — try another.", showBoard = false)
        }
    }

    private suspend fun collectEvents() {
        socket.events.collect { (event, data) ->
            when {
                event.endsWith("/move") -> handleMove(data)
                event.endsWith("/gamedata") -> handleSnapshot(data)
                event.endsWith("/phase") -> handlePhase(data)
                event.endsWith("/removed_stones_accepted") -> handleRemovalAccepted(data)
                event.endsWith("/chat") -> handleChat(data)
                event == "notification" -> handleNotification(data)
            }
        }
    }

    private fun handleSnapshot(data: JsonElement) {
        val obj = data.jsonObject
        val size = obj["width"]?.jsonPrimitive?.intOrNull ?: 19
        // Parse the move list once, then recover everything the snapshot omits —
        // captures, the live ko, whose turn it is, the last stone — by replaying it.
        val moveList = obj["moves"]?.jsonArray?.mapNotNull { mv ->
            val a = mv.jsonArray
            val mx = a.getOrNull(0)?.jsonPrimitive?.intOrNull
            val my = a.getOrNull(1)?.jsonPrimitive?.intOrNull
            if (mx != null && my != null) mx to my else null
        } ?: emptyList()
        val replay = replayMoves(moveList, size)
        nextColor = replay.nextColor

        // Prefer the server's authoritative board array (captures and handicap already
        // applied); fall back to the locally replayed position when the field is absent.
        val boardArr = obj["board"]?.jsonArray
        val fresh = if (boardArr != null) {
            BoardState(size).also { b ->
                boardArr.forEachIndexed { y, rowEl ->
                    rowEl.jsonArray.forEachIndexed { x, cellEl ->
                        when (cellEl.jsonPrimitive.intOrNull) {
                            1 -> b.set(x, y, Stone.BLACK)
                            2 -> b.set(x, y, Stone.WHITE)
                        }
                    }
                }
            }
        } else {
            replay.board
        }

        val players = obj["players"]?.jsonObject
        val blackPlayer = players?.get("black")?.jsonObject
        val whitePlayer = players?.get("white")?.jsonObject
        _playerNames.value =
            (blackPlayer?.get("username")?.jsonPrimitive?.contentOrNull ?: "Black") to
            (whitePlayer?.get("username")?.jsonPrimitive?.contentOrNull ?: "White")

        blackId = blackPlayer?.get("id")?.jsonPrimitive?.intOrNull ?: 0
        whiteId = whitePlayer?.get("id")?.jsonPrimitive?.intOrNull ?: 0
        if (myPlayerId != 0) {
            _myColor.value = when (myPlayerId) {
                blackId -> Stone.BLACK
                whiteId -> Stone.WHITE
                else -> null
            }
        }

        _captures.value = replay.capturedByBlack to replay.capturedByWhite
        koPoint = replay.koPoint
        _board.value = fresh
        _lastMove.value = replay.lastMove
        proposedRemoval = obj["removed"]?.jsonPrimitive?.contentOrNull ?: proposedRemoval
        updateTurn()

        // The snapshot is the game's confirmation. It also carries the phase: a game
        // we just opened may already be in scoring or finished (with outcome/winner).
        when (obj["phase"]?.jsonPrimitive?.contentOrNull) {
            "finished" -> applyFinished(
                outcome = obj["outcome"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                winnerId = obj["winner"]?.jsonPrimitive?.intOrNull,
            )
            "stone removal" -> enterScoring()
            else -> if (_phase.value !is GamePhase.Over) _phase.value = GamePhase.Playing
        }
    }

    private fun handlePhase(data: JsonElement) {
        when (data.jsonPrimitive.contentOrNull) {
            // The broadcast says the game ended but not who won; fetch the result.
            "finished" -> {
                if (_phase.value is GamePhase.Over) return
                viewModelScope.launch {
                    rest.fetchGameResult(gameId)
                        .onSuccess { applyFinished(it.outcome, it.winnerId) }
                        .onFailure { applyFinished(outcome = "", winnerId = null) }
                }
            }
            "stone removal" -> enterScoring()
            "play" -> if (_phase.value !is GamePhase.Over) {
                _phase.value = GamePhase.Playing
                updateTurn()
            }
        }
    }

    private fun enterScoring() {
        if (_phase.value !is GamePhase.Over) _phase.value = GamePhase.Scoring
    }

    /**
     * The opponent (or its bot) accepted a dead-stone set. Capture it verbatim so our
     * own acceptance matches exactly — OGS only concludes the game when both sides
     * accept identical stones.
     */
    private fun handleRemovalAccepted(data: JsonElement) {
        val obj = data.jsonObject
        if (obj["player_id"]?.jsonPrimitive?.intOrNull == myPlayerId) return
        obj["stones"]?.jsonPrimitive?.contentOrNull?.let { proposedRemoval = it }
    }

    private fun updateTurn() {
        val mine = _myColor.value
        _myTurn.value = mine != null && mine == nextColor
    }

    /** Append a received chat line. No-op for non-text bodies (shared variations). */
    private fun handleChat(data: JsonElement) {
        val message = parseGameChat(data) ?: return
        _chat.value = _chat.value + message
    }

    /**
     * Bot challenges are accepted optimistically by the REST endpoint (it returns a
     * game id immediately), but the bot can still decline over the socket — most often
     * because it is already at its concurrent-game limit.
     */
    private fun handleNotification(data: JsonElement) {
        val obj = data.jsonObject
        if (obj["type"]?.jsonPrimitive?.contentOrNull != "gameOfferRejected") return
        if (obj["game_id"]?.jsonPrimitive?.longOrNull != gameId) return
        val reason = obj["message"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: "The bot declined the challenge."
        end("Challenge declined: $reason", showBoard = false)
    }

    private fun applyFinished(outcome: String, winnerId: Int?) {
        val winnerName = when (winnerId) {
            blackId -> _playerNames.value.first
            whiteId -> _playerNames.value.second
            else -> null
        }
        end(
            message = buildString {
                append("Game over")
                if (winnerName != null) append(" — $winnerName wins")
                if (outcome.isNotBlank()) append(if (winnerName != null) " by $outcome" else " — $outcome")
            },
            showBoard = true,
        )
    }

    /** Move to the terminal state, unless already there (events can arrive twice). */
    private fun end(message: String, showBoard: Boolean) {
        if (_phase.value is GamePhase.Over) return
        _phase.value = GamePhase.Over(message, showBoard)
    }

    private fun handleMove(data: JsonElement) {
        val move = data.jsonObject["move"]?.jsonArray ?: return
        val x = move.getOrNull(0)?.jsonPrimitive?.intOrNull ?: return
        val y = move.getOrNull(1)?.jsonPrimitive?.intOrNull ?: return
        val b = BoardState(_board.value.size).also { dst ->
            val src = _board.value
            for (row in 0 until src.size) for (col in 0 until src.size) dst.grid[row][col] = src.grid[row][col]
        }
        if (x >= 0 && y >= 0) {
            val captured = b.place(x, y, nextColor)
            if (captured.isNotEmpty()) {
                val (cb, cw) = _captures.value
                _captures.value =
                    if (nextColor == Stone.BLACK) (cb + captured.size) to cw else cb to (cw + captured.size)
            }
            koPoint = b.koPointAfter(x, y, captured)
            _lastMove.value = x to y
        } else {
            koPoint = null // a pass dissolves any ko
            _lastMove.value = null
        }
        nextColor = if (nextColor == Stone.BLACK) Stone.WHITE else Stone.BLACK
        _board.value = b
        updateTurn()
    }

    /**
     * Local legality of a prospective move at (x, y), for immediate invalid-tap
     * feedback. Uses the live board plus any active ko point; the server remains
     * authoritative, so this only reports the always-illegal cases (see [MoveLegality]).
     */
    fun legalityOf(x: Int, y: Int): MoveLegality {
        val color = _myColor.value ?: return MoveLegality.LEGAL
        return _board.value.legality(x, y, color, koPoint)
    }

    fun tap(x: Int, y: Int) {
        if (_phase.value != GamePhase.Playing) return
        socket.gameMove(gameId, OgsCoord.encode(x, y))
    }

    fun resign() {
        if (_phase.value != GamePhase.Playing) return
        socket.gameResign(gameId)
    }

    /** Scoring: accept the proposed score; the game finishes once the opponent agrees. */
    fun acceptScore() {
        if (_phase.value != GamePhase.Scoring) return
        socket.acceptRemovedStones(gameId, myPlayerId, proposedRemoval)
    }

    /** Scoring: reject the proposed score and resume play. */
    fun resumeGame() {
        if (_phase.value != GamePhase.Scoring) return
        socket.rejectRemovedStones(gameId, myPlayerId)
    }

    private suspend fun keepAlive() {
        while (viewModelScope.isActive) {
            socket.ping()
            delay(10_000)
        }
    }

    override fun onCleared() {
        if (gameId != 0L) socket.gameDisconnect(gameId)
        socket.close()
    }
}
