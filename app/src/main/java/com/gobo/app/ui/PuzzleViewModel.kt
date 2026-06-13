package com.gobo.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gobo.app.board.BoardState
import com.gobo.app.board.Stone
import com.gobo.app.net.OgsRest
import com.gobo.app.net.Puzzle
import com.gobo.app.net.PuzzleNode
import com.gobo.app.net.PuzzleOutcome
import com.gobo.app.net.PuzzleStep
import com.gobo.app.net.puzzleHints
import com.gobo.app.net.puzzleStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PuzzleSolveUiState {
    data object Loading : PuzzleSolveUiState
    data class Ready(val puzzle: Puzzle) : PuzzleSolveUiState
    data class Error(val message: String) : PuzzleSolveUiState
}

/** Progress through the current puzzle attempt. */
enum class PuzzleStatus { Solving, Solved, Failed }

/** Result of a board tap, so the screen knows whether to flash an off-tree (rejected) move. */
enum class PuzzleTap { ACCEPTED, OFF_TREE, IGNORED }

/**
 * Drives one puzzle attempt: loads the puzzle, builds the starting position, and walks the solution
 * tree as the player taps. The opponent's replies are played automatically from the tree (we never
 * reimplement Go rules beyond [BoardState]'s capture logic, which the board reuses so dead stones
 * disappear). [prevId]/[nextId] come from the collection's ordered list for prev/next navigation.
 */
class PuzzleViewModel(
    private val rest: OgsRest,
    private val puzzleId: Long,
) : ViewModel() {

    private val _state = MutableStateFlow<PuzzleSolveUiState>(PuzzleSolveUiState.Loading)
    val state = _state.asStateFlow()

    private val _board = MutableStateFlow(BoardState(19))
    val board = _board.asStateFlow()

    private val _status = MutableStateFlow(PuzzleStatus.Solving)
    val status = _status.asStateFlow()

    private val _lastMove = MutableStateFlow<Pair<Int, Int>?>(null)
    val lastMove = _lastMove.asStateFlow()

    /** Hinted intersection(s) for the current position; empty unless the player asked for a hint. */
    private val _hint = MutableStateFlow<List<Pair<Int, Int>>>(emptyList())
    val hint = _hint.asStateFlow()

    /** Adjacent puzzles in the same collection (null at the ends), for the prev/next buttons. */
    private val _prevId = MutableStateFlow<Long?>(null)
    val prevId = _prevId.asStateFlow()
    private val _nextId = MutableStateFlow<Long?>(null)
    val nextId = _nextId.asStateFlow()

    private var puzzle: Puzzle? = null
    private var currentNode: PuzzleNode = PuzzleNode(-1, -1, emptyList())
    private var playerColor: Stone = Stone.BLACK

    init {
        viewModelScope.launch {
            rest.fetchPuzzle(puzzleId)
                .onSuccess { p ->
                    puzzle = p
                    playerColor = p.playerColor
                    reset()
                    _state.value = PuzzleSolveUiState.Ready(p)
                }
                .onFailure {
                    _state.value = PuzzleSolveUiState.Error(it.message ?: "Failed to load puzzle")
                }
        }
        // Best-effort: a failure here just leaves prev/next disabled.
        viewModelScope.launch {
            rest.fetchCollectionSummary(puzzleId).onSuccess { refs ->
                val i = refs.indexOfFirst { it.id == puzzleId }
                if (i >= 0) {
                    _prevId.value = refs.getOrNull(i - 1)?.id
                    _nextId.value = refs.getOrNull(i + 1)?.id
                }
            }
        }
    }

    /** Restore the starting position and reset progress to the top of the tree. */
    fun reset() {
        val p = puzzle ?: return
        val size = maxOf(p.width, p.height)
        val fresh = BoardState(size)
        p.initialBlack.forEach { (x, y) -> fresh.set(x, y, Stone.BLACK) }
        p.initialWhite.forEach { (x, y) -> fresh.set(x, y, Stone.WHITE) }
        _board.value = fresh
        currentNode = p.moveTree
        _status.value = PuzzleStatus.Solving
        _lastMove.value = null
        _hint.value = emptyList()
    }

    /**
     * Toggle the hint highlight for the current position on/off. When on, it marks the move(s) that
     * progress toward the solution (the non-wrong branches at the current tree node). No-op once the
     * puzzle is solved/failed — there's nothing left to play.
     */
    fun toggleHint() {
        if (_status.value != PuzzleStatus.Solving) return
        _hint.value = if (_hint.value.isEmpty()) puzzleHints(currentNode) else emptyList()
    }

    /**
     * Apply a player tap. Off-tree taps are rejected (the screen flashes them); a correct line
     * advances and auto-plays the opponent; a wrong move fails (retry via [reset]). No-ops once the
     * puzzle is already solved or failed.
     */
    fun tap(x: Int, y: Int): PuzzleTap {
        if (_status.value != PuzzleStatus.Solving) return PuzzleTap.IGNORED
        val opponentColor = if (playerColor == Stone.BLACK) Stone.WHITE else Stone.BLACK
        return when (val step = puzzleStep(currentNode, x, y)) {
            // An off-tree tap doesn't change the position, so keep any hint showing; otherwise the
            // board has moved on and the old hint no longer applies.
            PuzzleStep.OffTree -> PuzzleTap.OFF_TREE
            is PuzzleStep.Play -> {
                place(step.playerMove, playerColor)
                step.opponentMove?.let { place(it, opponentColor) }
                when (step.outcome) {
                    PuzzleOutcome.SOLVED -> _status.value = PuzzleStatus.Solved
                    PuzzleOutcome.FAILED -> _status.value = PuzzleStatus.Failed
                    PuzzleOutcome.CONTINUE -> currentNode = step.next
                }
                PuzzleTap.ACCEPTED
            }
        }
    }

    /** Place one stone on a fresh board copy (so the StateFlow emits), mark it as the last move, and
     *  retire any hint (the position has changed). */
    private fun place(move: Pair<Int, Int>, color: Stone) {
        _hint.value = emptyList()
        val src = _board.value
        val copy = BoardState(src.size).also { dst ->
            for (row in 0 until src.size) for (col in 0 until src.size) dst.grid[row][col] = src.grid[row][col]
        }
        copy.place(move.first, move.second, color)
        _board.value = copy
        _lastMove.value = move
    }
}
