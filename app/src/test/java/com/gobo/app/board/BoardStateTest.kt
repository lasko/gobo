package com.gobo.app.board

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Capture logic. Note grid is indexed [y][x]; set(x, y, s) writes grid[y][x].
 */
class BoardStateTest {

    @Test
    fun placesStone() {
        val b = BoardState(9)
        b.applyMove(2, 3, Stone.BLACK)
        assertEquals(Stone.BLACK, b.grid[3][2])
    }

    @Test
    fun capturesSingleSurroundedStone() {
        val b = BoardState(9)
        b.set(4, 4, Stone.WHITE)
        b.set(3, 4, Stone.BLACK)
        b.set(5, 4, Stone.BLACK)
        b.set(4, 3, Stone.BLACK)
        // Playing the final liberty removes the white stone.
        b.applyMove(4, 5, Stone.BLACK)
        assertEquals(Stone.EMPTY, b.grid[4][4])
    }

    @Test
    fun doesNotCaptureWhileLibertyRemains() {
        val b = BoardState(9)
        b.set(4, 4, Stone.WHITE)
        b.applyMove(3, 4, Stone.BLACK) // white keeps 3 liberties
        assertEquals(Stone.WHITE, b.grid[4][4])
    }

    @Test
    fun capturesMultiStoneGroup() {
        val b = BoardState(9)
        b.set(4, 4, Stone.WHITE)
        b.set(5, 4, Stone.WHITE)
        b.set(3, 4, Stone.BLACK)
        b.set(6, 4, Stone.BLACK)
        b.set(4, 3, Stone.BLACK)
        b.set(5, 3, Stone.BLACK)
        b.set(4, 5, Stone.BLACK)
        b.applyMove(5, 5, Stone.BLACK)
        assertEquals(Stone.EMPTY, b.grid[4][4])
        assertEquals(Stone.EMPTY, b.grid[4][5])
    }

    @Test
    fun capturesStoneInCorner() {
        val b = BoardState(9)
        b.set(0, 0, Stone.WHITE)
        b.set(1, 0, Stone.BLACK)
        b.applyMove(0, 1, Stone.BLACK) // corner stone has only two liberties
        assertEquals(Stone.EMPTY, b.grid[0][0])
    }
}
