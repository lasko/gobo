package com.gobo.app.board

/**
 * Everything OGS omits from a game snapshot but the UI needs, derived by replaying
 * the move list with local capture logic: the resulting position, each side's running
 * prisoner count, the live simple-ko point, whose turn is next, and the last placed
 * stone. See [replayMoves].
 */
data class ReplayState(
    val board: BoardState,
    /** Stones captured by black, i.e. black's prisoner count. */
    val capturedByBlack: Int,
    /** Stones captured by white, i.e. white's prisoner count. */
    val capturedByWhite: Int,
    val koPoint: Pair<Int, Int>?,
    val nextColor: Stone,
    val lastMove: Pair<Int, Int>?,
)

/**
 * Replay [moves] from an empty board of [size], applying capture logic, to recover the
 * derived state a snapshot leaves out. Pure (no JSON / Android deps) so [ReplayState]
 * can be unit-tested directly; [com.gobo.app.ui.GameViewModel] parses the wire payload
 * and feeds the coordinates here.
 *
 * Moves are (x, y) with black moving first; a pass is any move with a negative
 * coordinate. Every move — including a pass — hands the turn to the opponent, so a pass
 * also dissolves any standing ko.
 */
fun replayMoves(moves: List<Pair<Int, Int>>, size: Int): ReplayState {
    val board = BoardState(size)
    var capturedByBlack = 0
    var capturedByWhite = 0
    var koPoint: Pair<Int, Int>? = null
    var lastMove: Pair<Int, Int>? = null
    var color = Stone.BLACK
    for ((x, y) in moves) {
        if (x >= 0 && y >= 0) {
            val captured = board.place(x, y, color)
            if (color == Stone.BLACK) capturedByBlack += captured.size else capturedByWhite += captured.size
            koPoint = board.koPointAfter(x, y, captured)
            lastMove = x to y
        } else {
            koPoint = null
            lastMove = null
        }
        color = if (color == Stone.BLACK) Stone.WHITE else Stone.BLACK
    }
    return ReplayState(board, capturedByBlack, capturedByWhite, koPoint, color, lastMove)
}
