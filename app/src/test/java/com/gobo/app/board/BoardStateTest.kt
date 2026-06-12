package com.gobo.app.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun legalOnEmptyPointWithLiberty() {
        val b = BoardState(9)
        assertEquals(MoveLegality.LEGAL, b.legality(4, 4, Stone.BLACK))
    }

    @Test
    fun occupiedPointIsIllegal() {
        val b = BoardState(9)
        b.set(4, 4, Stone.WHITE)
        assertEquals(MoveLegality.OCCUPIED, b.legality(4, 4, Stone.BLACK))
    }

    @Test
    fun fillingOwnLastLibertyIsSuicide() {
        // White surrounds the single empty point at (0,0); Black playing there
        // self-captures without taking anything.
        val b = BoardState(9)
        b.set(1, 0, Stone.WHITE)
        b.set(0, 1, Stone.WHITE)
        assertEquals(MoveLegality.SUICIDE, b.legality(0, 0, Stone.BLACK))
    }

    @Test
    fun captureIsNotSuicide() {
        // The played stone would have no liberty of its own, but it captures the
        // surrounded white group first, so the point becomes legal.
        val b = BoardState(9)
        b.set(0, 0, Stone.WHITE)
        b.set(1, 0, Stone.BLACK)
        // Black at (0,1) takes White(0,0); legal despite filling the shared region.
        assertEquals(MoveLegality.LEGAL, b.legality(0, 1, Stone.BLACK))
    }

    @Test
    fun koPointIsDetectedAndForbidden() {
        // Standard ko shape around row 4. A lone white stone at (2,4) is hugged by
        // black on three sides, and black's capturing point (3,4) is in turn hugged
        // by white on its other three sides:
        //          (2,3)B  (3,3)W
        //   (1,4)B  (2,4)W  (3,4)·  (4,4)W
        //          (2,5)B  (3,5)W
        val b = BoardState(9)
        b.set(2, 4, Stone.WHITE)
        b.set(2, 3, Stone.BLACK)
        b.set(2, 5, Stone.BLACK)
        b.set(1, 4, Stone.BLACK)
        b.set(3, 3, Stone.WHITE)
        b.set(3, 5, Stone.WHITE)
        b.set(4, 4, Stone.WHITE)

        // Black plays (3,4): it captures the lone white at (2,4) and is left a lone
        // stone whose only liberty is that just-vacated point — i.e. a ko.
        val captured = b.place(3, 4, Stone.BLACK)
        assertEquals(listOf(2 to 4), captured)
        val ko = b.koPointAfter(3, 4, captured)
        assertEquals(2 to 4, ko)

        // White may not immediately retake at the ko point...
        assertEquals(MoveLegality.KO, b.legality(2, 4, Stone.WHITE, ko))
        // ...but the same point is fine once the ko is resolved (no ko point in force).
        assertEquals(MoveLegality.LEGAL, b.legality(2, 4, Stone.WHITE, null))
    }

    @Test
    fun noKoWhenCapturingStoneHasExtraLiberty() {
        // A capture of one stone by a stone that keeps a second liberty is not a ko.
        val b = BoardState(9)
        b.set(0, 0, Stone.WHITE)
        b.set(1, 0, Stone.BLACK)
        val captured = b.place(0, 1, Stone.BLACK) // takes corner white, but (0,1) has liberty (0,2)
        assertEquals(listOf(0 to 0), captured)
        assertNull(b.koPointAfter(0, 1, captured))
    }
}
