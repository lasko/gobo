package com.gobo.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gobo.app.board.BoardState
import com.gobo.app.board.MoveLegality
import com.gobo.app.board.OgsCoord
import com.gobo.app.board.Stone
import com.gobo.app.board.replayMoves
import com.gobo.app.net.ChatMessage
import com.gobo.app.net.ConnectionState
import com.gobo.app.net.GameClock
import com.gobo.app.net.OgsRest
import com.gobo.app.net.OgsSocket
import com.gobo.app.net.appendChat
import com.gobo.app.net.parseClock
import com.gobo.app.net.parseGameChat
import com.gobo.app.net.parseGameChatLog
import com.gobo.app.net.parseUndoMoveCount
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

/**
 * A position being reviewed on the finished-game screen: the board after [index] of [total] moves,
 * with the marker on that move and the prisoner counts at that point. Rebuilt purely by replaying
 * the move list, so stepping through a game reuses the same tested logic as the live board.
 */
data class ReviewState(
    val index: Int,
    val total: Int,
    val board: BoardState,
    val lastMove: Pair<Int, Int>?,
    val captures: Pair<Int, Int>,
)

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

    /** Colour to move next. Used for the spectator's "whose turn" status (a watcher has no side). */
    private val _toMove = MutableStateFlow(Stone.BLACK)
    val toMove = _toMove.asStateFlow()

    /** (x, y) of the most recent stone, for the last-move marker; null after a pass. */
    private val _lastMove = MutableStateFlow<Pair<Int, Int>?>(null)
    val lastMove = _lastMove.asStateFlow()

    /** Stones captured (by black, by white) — i.e. each player's prisoner count. */
    private val _captures = MutableStateFlow(0 to 0)
    val captures = _captures.asStateFlow()

    /** In-game chat, oldest first. Stays empty unless chat was opted in for this game. */
    private val _chat = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chat = _chat.asStateFlow()

    /**
     * Latest clock snapshot (both players' times + the move anchor), or null before the first
     * `clock`/gamedata arrives. The UI ticks a local 1 Hz countdown off this anchor — OGS only
     * pushes a fresh clock on each move, not every second.
     */
    private val _clock = MutableStateFlow<GameClock?>(null)
    val clock = _clock.asStateFlow()

    /**
     * Whether the Undo button is actionable: true only while [GamePhase.Playing] when the most
     * recent move on the board is the local player's (i.e. it's the opponent's turn) and no undo
     * is already pending. Bots accept undo, so this is the realistic use — take back a misclick
     * before the opponent has replied. False otherwise (the button shows disabled).
     */
    private val _undoEnabled = MutableStateFlow(false)
    val undoEnabled = _undoEnabled.asStateFlow()

    /**
     * True while the realtime socket is dropped and reconnecting. The board freezes during the gap;
     * the UI shows a banner so it reads as "reconnecting", not a hung game. On reconnect the re-sent
     * `game/connect` triggers a fresh `gamedata` snapshot that resyncs everything.
     */
    private val _reconnecting = MutableStateFlow(false)
    val reconnecting = _reconnecting.asStateFlow()

    /**
     * Move-by-move review of a finished game, or null while the game is live. Initialized at the
     * final position when the game ends (with a board); the screen steps [reviewStep]/[reviewJump]
     * through it. Each position is a pure [replayMoves] of the move-list prefix.
     */
    private val _review = MutableStateFlow<ReviewState?>(null)
    val review = _review.asStateFlow()

    private var gameId: Long = 0
    /** The open challenge we posted and are waiting on (0 when this isn't a waiting-for-opponent
     *  flow). While [GamePhase.Connecting] we keep it alive; [cancelOpenChallenge] withdraws it. */
    private var challengeId: Long = 0
    private var nextColor = Stone.BLACK
    private var blackId = 0
    private var whiteId = 0
    /** Point forbidden to the side now to move by simple ko, or null. Drives local KO flashes. */
    private var koPoint: Pair<Int, Int>? = null
    /** Dead-stone set proposed during scoring, in OGS encoding; echoed back on accept. */
    private var proposedRemoval = ""
    /** Moves played so far; the move a chat line is attached to when sending. */
    private var moveNumber = 0
    /**
     * The full move list (x, y), passes as (-1, -1) — kept so an accepted undo can rebuild the
     * position by dropping the last move(s) and replaying ([replayMoves]); OGS sends no fresh
     * snapshot on undo, it expects the client to step back locally.
     */
    private val moves = mutableListOf<Pair<Int, Int>>()
    /**
     * True between sending an undo request and the opponent accepting/declining (or superseding)
     * it. Surfaced so the UI can show a transient "requested" status — many bots (amybot included)
     * ignore undo, so the player needs feedback that the request went out even when nothing reverts.
     */
    private val _undoRequested = MutableStateFlow(false)
    val undoRequested = _undoRequested.asStateFlow()
    /** Whether chat was opted in for this game — gates reading the snapshot's chat log. */
    private var chatEnabled = false

    /**
     * @param chatEnabled when true, request chat on connect so `game/<id>/chat` events
     *   arrive and populate [chat]. Off by default keeps the connect minimal (no chat).
     * @param challengeId when non-zero, this is an open challenge we just posted and are waiting on:
     *   we keep it alive (~1/s) instead of timing out, and reveal [GamePhase.Playing] when an
     *   opponent accepts (their acceptance is the `gamedata` snapshot that arrives on `game/connect`).
     */
    fun start(gameId: Long, chatEnabled: Boolean = false, challengeId: Long = 0L) {
        this.gameId = gameId
        this.chatEnabled = chatEnabled
        this.challengeId = challengeId
        viewModelScope.launch {
            val cfg = rest.fetchUiConfig().getOrElse {
                end("Couldn't connect: ${it.message}", showBoard = false)
                return@launch
            }
            // Subscribe before connecting: the events flow has replay=0, so the burst that
            // arrives right after game/connect (gamedata, chat log) must not race the collector.
            launch { collectEvents() }
            launch { collectConnection() }
            socket.connect {
                socket.authenticate(cfg.userJwt)
                socket.gameConnect(gameId, chat = chatEnabled)
            }
            launch { keepAlive() }
            if (challengeId != 0L) launch { challengeKeepAlive() } else launch { startTimeout() }
        }
    }

    /** Guard against a bot that neither accepts nor explicitly declines in time. */
    private suspend fun startTimeout() {
        delay(25_000)
        if (_phase.value is GamePhase.Connecting) {
            end("The game didn't start. The bot may be busy — try another.", showBoard = false)
        }
    }

    /**
     * While waiting for someone to accept our open challenge, ping `challenge/keepalive` ~1/s so
     * OGS doesn't expire the seek. Runs only during [GamePhase.Connecting]; once an opponent accepts,
     * the `gamedata` snapshot flips us to [GamePhase.Playing] and the loop exits.
     */
    private suspend fun challengeKeepAlive() {
        while (viewModelScope.isActive && _phase.value is GamePhase.Connecting) {
            socket.challengeKeepalive(challengeId, gameId)
            delay(1_000)
        }
    }

    /** Withdraw the open challenge we're waiting on (the UI then navigates away, closing the socket). */
    fun cancelOpenChallenge() {
        val id = challengeId
        if (id != 0L) viewModelScope.launch { rest.cancelChallenge(id) }
    }

    /** Mirror the socket's connection lifecycle into a simple reconnecting flag for the UI. */
    private suspend fun collectConnection() {
        socket.connection.collect { _reconnecting.value = it == ConnectionState.Reconnecting }
    }

    private suspend fun collectEvents() {
        socket.events.collect { (event, data) ->
            when {
                event.endsWith("/move") -> handleMove(data)
                event.endsWith("/gamedata") -> handleSnapshot(data)
                event.endsWith("/phase") -> handlePhase(data)
                event.endsWith("/removed_stones_accepted") -> handleRemovalAccepted(data)
                event.endsWith("/undo_accepted") -> handleUndoAccepted(data)
                event.endsWith("/undo_canceled") -> handleUndoCanceled()
                event.endsWith("/clock") -> handleClock(data)
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
        moveNumber = moveList.size
        // Track the move list so an accepted undo can replay a truncated copy.
        moves.clear()
        moves.addAll(moveList)

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
        // The snapshot carries the current clock too; recover it so the countdown survives a
        // mid-game (re)connect, not just live `clock` events.
        obj["clock"]?.let { _clock.value = parseClock(it, System.currentTimeMillis()) }
        proposedRemoval = obj["removed"]?.jsonPrimitive?.contentOrNull ?: proposedRemoval
        updateTurn()

        // Pick up chat sent before we connected (e.g. a bot's opening greeting), which the
        // snapshot carries in `chat_log`. appendChat de-dupes against any live copies.
        if (chatEnabled) {
            parseGameChatLog(data).forEach { _chat.value = appendChat(_chat.value, it) }
        }

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
        _toMove.value = nextColor
        refreshUndoAvailability()
    }

    /**
     * Undo is offered only to take back the local player's own most recent move: while playing,
     * with at least one move on the board, the side that just moved ([nextColor]'s opponent) is
     * us, and no request is already in flight. After the opponent replies the last move is theirs,
     * so the button disables — matching the issue's "your own most recent move" rule.
     */
    private fun refreshUndoAvailability() {
        val mine = _myColor.value
        _undoEnabled.value = !_undoRequested.value &&
            _phase.value == GamePhase.Playing &&
            mine != null &&
            moves.isNotEmpty() &&
            mine != nextColor
    }

    /**
     * Append a received chat line, ignoring non-text bodies and any line we already have —
     * OGS re-sends the chat log on lifecycle events (e.g. at game end), so the append must
     * be idempotent (see [appendChat]).
     */
    private fun handleChat(data: JsonElement) {
        val message = parseGameChat(data) ?: return
        _chat.value = appendChat(_chat.value, message)
    }

    /** A fresh clock arrives on every move; the UI ticks the live countdown off its anchor. */
    private fun handleClock(data: JsonElement) {
        parseClock(data, System.currentTimeMillis())?.let { _clock.value = it }
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
        // A finished game with a board is reviewable: start at the final position.
        if (showBoard) _review.value = buildReview(moves.size)
    }

    /** The reviewable position after [index] moves (clamped), rebuilt by replaying that prefix. */
    private fun buildReview(index: Int): ReviewState {
        val clamped = index.coerceIn(0, moves.size)
        val r = replayMoves(moves.take(clamped), _board.value.size)
        return ReviewState(clamped, moves.size, r.board, r.lastMove, r.capturedByBlack to r.capturedByWhite)
    }

    /** Step the review by [delta] moves (e.g. -1 prev, +1 next); no-op unless reviewing. */
    fun reviewStep(delta: Int) {
        val cur = _review.value ?: return
        _review.value = buildReview(cur.index + delta)
    }

    /** Jump the review to an absolute move [index] (clamped); no-op unless reviewing. */
    fun reviewJump(index: Int) {
        if (_review.value == null) return
        _review.value = buildReview(index)
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
        moveNumber++
        moves.add(x to y)
        // A real move from either side supersedes any pending undo (e.g. the bot replied
        // instead of accepting), so clear it before re-evaluating availability.
        _undoRequested.value = false
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

    /**
     * Request to take back your last move. No-op unless [undoEnabled]; we mark the request pending
     * (disabling the button) and wait for the opponent's `undo_accepted` — bots accept, humans may
     * not, so nothing reverts optimistically.
     */
    fun requestUndo() {
        if (!_undoEnabled.value) return
        _undoRequested.value = true
        refreshUndoAvailability()
        socket.requestUndo(gameId, moveNumber)
    }

    /**
     * The opponent accepted our undo. OGS sends no fresh snapshot — it expects the client to step
     * back — so drop the taken-back move(s) and rebuild the position by replaying what remains.
     */
    private fun handleUndoAccepted(data: JsonElement) {
        if (moves.isEmpty()) return
        repeat(parseUndoMoveCount(data)) { if (moves.isNotEmpty()) moves.removeAt(moves.lastIndex) }
        val replay = replayMoves(moves.toList(), _board.value.size)
        nextColor = replay.nextColor
        moveNumber = moves.size
        koPoint = replay.koPoint
        _board.value = replay.board
        _captures.value = replay.capturedByBlack to replay.capturedByWhite
        _lastMove.value = replay.lastMove
        _undoRequested.value = false
        updateTurn()
    }

    /** The undo request was withdrawn/declined; re-enable the button if still our move. */
    private fun handleUndoCanceled() {
        _undoRequested.value = false
        refreshUndoAvailability()
    }

    /**
     * Send an in-game chat line (no-op for blank text). Identity is already established by
     * the authenticated socket; our own line appears once the server echoes it back as a
     * `game/<id>/chat` event.
     */
    fun sendChat(body: String) {
        val text = body.trim()
        if (text.isEmpty() || gameId == 0L) return
        socket.sendChat(gameId, text, moveNumber)
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

    /**
     * Tear down the socket. Called from [onCleared], but also explicitly from the UI on leaving the
     * game (the screen creates this via `remember`, not `viewModel()`, so `onCleared` won't fire on
     * its own — and a live socket would keep auto-reconnecting in the background).
     */
    fun close() {
        if (gameId != 0L) socket.gameDisconnect(gameId)
        socket.close()
    }

    override fun onCleared() = close()
}
