package com.gobo.app.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Replaying a move list recovers the derived state OGS leaves out of a snapshot.
 * Coordinates are (x, y); a pass is (-1, -1).
 */
class MoveReplayTest {

    @Test
    fun emptyGameStartsWithBlackToMove() {
        val s = replayMoves(emptyList(), 9)
        assertEquals(Stone.BLACK, s.nextColor)
        assertEquals(0, s.capturedByBlack)
        assertEquals(0, s.capturedByWhite)
        assertNull(s.koPoint)
        assertNull(s.lastMove)
    }

    @Test
    fun turnAlternatesAndLastMoveTracksThePlayedStone() {
        val s = replayMoves(listOf(2 to 2, 3 to 3, 4 to 4), 9)
        assertEquals(Stone.WHITE, s.nextColor) // 3 moves played -> white is next
        assertEquals(4 to 4, s.lastMove)
        assertEquals(Stone.BLACK, s.board.grid[2][2])
        assertEquals(Stone.WHITE, s.board.grid[3][3])
    }

    @Test
    fun captureIsCreditedToTheCapturingColor() {
        // Black surrounds a lone white stone at (4,4); white tenukis while black closes
        // each liberty, and black's last move takes the stone.
        val moves = listOf(
            3 to 4, // B
            4 to 4, // W (the victim)
            5 to 4, // B
            0 to 0, // W tenuki
            4 to 3, // B
            0 to 1, // W tenuki
            4 to 5, // B closes the last liberty -> captures white(4,4)
        )
        val s = replayMoves(moves, 9)
        assertEquals(1, s.capturedByBlack)
        assertEquals(0, s.capturedByWhite)
        assertEquals(Stone.EMPTY, s.board.grid[4][4])
    }

    @Test
    fun passDissolvesKoAndClearsLastMove() {
        // A trailing pass leaves no last-move marker and no ko point, and still flips turn.
        val moves = listOf(
            2 to 4, // B
            2 to 3, // W
            1 to 4, // B
            -1 to -1, // W pass
        )
        val s = replayMoves(moves, 9)
        assertNull(s.lastMove)                 // last action was a pass
        assertNull(s.koPoint)                  // a pass dissolves any ko
        assertEquals(Stone.BLACK, s.nextColor) // 4 actions played -> black next
    }

    @Test
    fun koReplaySurfacesForbiddenPoint() {
        // Black-to-capture ko, mirroring BoardStateTest's shape but sequenced so black
        // plays the capturing stone last. White victim (2,4) is hugged by black on three
        // sides; black's capturing point (3,4) is hugged by white on its other three.
        val moves = listOf(
            2 to 3, // B
            2 to 4, // W (victim)
            2 to 5, // B
            3 to 3, // W
            1 to 4, // B
            3 to 5, // W
            7 to 7, // B tenuki
            4 to 4, // W (completes the white wall around black's capturing point)
            3 to 4, // B captures white(2,4); lone stone, one liberty -> ko
        )
        val s = replayMoves(moves, 9)
        assertEquals(2 to 4, s.koPoint)        // white may not immediately retake here
        assertEquals(1, s.capturedByBlack)
        assertEquals(Stone.EMPTY, s.board.grid[4][2]) // grid[y][x] = (2,4)
        assertEquals(Stone.WHITE, s.nextColor) // black just moved
    }
}
