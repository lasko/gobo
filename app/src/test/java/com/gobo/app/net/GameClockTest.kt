package com.gobo.app.net

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameClockTest {

    private fun clock(s: String, deviceNowMs: Long = 0L) =
        parseClock(Json.parseToJsonElement(s), deviceNowMs)

    // ---- parseClock ----

    @Test
    fun parsesByoyomiClock() {
        val c = clock(
            """
            {"game_id":1,"current_player":100,"black_player_id":100,"white_player_id":200,
             "last_move":1000000,
             "black_time":{"thinking_time":84.9,"periods":3,"period_time":30},
             "white_time":{"thinking_time":66.6,"periods":3,"period_time":30}}
            """.trimIndent(),
        )!!
        assertEquals(100, c.currentPlayerId)
        assertEquals(100, c.blackPlayerId)
        assertEquals(200, c.whitePlayerId)
        assertEquals(1000000L, c.lastMoveMs)
        assertEquals(84.9, c.black.thinkingTime, 1e-6)
        assertEquals(3, c.black.periodsLeft)
        assertEquals(30.0, c.black.periodTime!!, 1e-6)
        assertEquals(0L, c.skewMs) // no "now" field
    }

    @Test
    fun parsesFischerEntryWithoutPeriods() {
        val c = clock(
            """
            {"current_player":1,"black_player_id":1,"white_player_id":2,"last_move":0,
             "black_time":{"thinking_time":300.0,"skip_bonus":false},
             "white_time":{"thinking_time":120.0,"skip_bonus":false}}
            """.trimIndent(),
        )!!
        assertNull(c.black.periodsLeft)
        assertNull(c.black.periodTime)
        assertEquals(300.0, c.black.thinkingTime, 1e-6)
    }

    @Test
    fun derivesSkewFromServerNow() {
        // server says it's 5000ms later than the device clock.
        val c = clock(
            """
            {"current_player":1,"black_player_id":1,"white_player_id":2,"last_move":0,"now":5000,
             "black_time":{"thinking_time":10.0},"white_time":{"thinking_time":10.0}}
            """.trimIndent(),
            deviceNowMs = 0L,
        )!!
        assertEquals(5000L, c.skewMs)
    }

    @Test
    fun returnsNullWithoutCurrentPlayer() {
        assertNull(
            clock("""{"black_time":{"thinking_time":1.0},"white_time":{"thinking_time":1.0}}"""),
        )
    }

    @Test
    fun parsesSimpleBareNumberAsDeadline() {
        // "simple" time control: a bare epoch-seconds number = the move deadline, normalized to ms.
        val entry = parseTimeEntry(Json.parseToJsonElement("1700000000"))!!
        assertEquals(1_700_000_000_000L, entry.moveDeadlineMs)
        // A small bare number is a plain seconds-remaining budget, not a deadline.
        val small = parseTimeEntry(Json.parseToJsonElement("45"))!!
        assertNull(small.moveDeadlineMs)
        assertEquals(45.0, small.thinkingTime, 1e-6)
    }

    // ---- readClock: ticking vs frozen ----

    @Test
    fun opponentClockIsFrozenRegardlessOfElapsed() {
        val entry = TimeEntry(thinkingTime = 100.0)
        // 40s of wall-time passed, but this is not the side to move.
        val r = readClock(entry, isCurrentPlayer = false, anchorMs = 0, nowMs = 40_000)
        assertEquals(100L, r.seconds)
        assertFalse(r.expired)
    }

    @Test
    fun currentPlayerMainTimeCountsDown() {
        val entry = TimeEntry(thinkingTime = 100.0)
        val r = readClock(entry, isCurrentPlayer = true, anchorMs = 0, nowMs = 40_000)
        assertEquals(60L, r.seconds)
        assertFalse(r.inByoyomi)
        assertNull(r.periodsLeft)
    }

    @Test
    fun fischerExpiresAtZeroWithNoPeriods() {
        val entry = TimeEntry(thinkingTime = 30.0)
        val r = readClock(entry, isCurrentPlayer = true, anchorMs = 0, nowMs = 35_000)
        assertEquals(0L, r.seconds)
        assertTrue(r.expired)
        assertFalse(r.inByoyomi)
    }

    // ---- readClock: byoyomi ----

    @Test
    fun entersByoyomiWhenMainTimeRunsOut() {
        // 10s main + 3x30s periods; 15s elapsed -> 5s into the first period.
        val entry = TimeEntry(thinkingTime = 10.0, periodsLeft = 3, periodTime = 30.0)
        val r = readClock(entry, isCurrentPlayer = true, anchorMs = 0, nowMs = 15_000)
        assertTrue(r.inByoyomi)
        assertEquals(25L, r.seconds)   // 30 - 5
        assertEquals(3, r.periodsLeft) // still in the first period
        assertFalse(r.expired)
    }

    @Test
    fun consumesWholePeriodsOnOverflow() {
        // 0 main + 3x30s; 50s elapsed -> first period (30) gone, 20s into the second.
        val entry = TimeEntry(thinkingTime = 0.0, periodsLeft = 3, periodTime = 30.0)
        val r = readClock(entry, isCurrentPlayer = true, anchorMs = 0, nowMs = 50_000)
        assertTrue(r.inByoyomi)
        assertEquals(2, r.periodsLeft)
        assertEquals(10L, r.seconds)   // 30 - (50 - 30)
    }

    @Test
    fun startsFromPeriodTimeLeftWhenMidPeriod() {
        // Reconnect mid-period: 19s left in the current period, 4s elapsed since the anchor.
        val entry = TimeEntry(
            thinkingTime = 0.0, periodsLeft = 3, periodTime = 30.0, periodTimeLeft = 19.0,
        )
        val r = readClock(entry, isCurrentPlayer = true, anchorMs = 0, nowMs = 4_000)
        assertEquals(15L, r.seconds) // 19 - 4
        assertEquals(3, r.periodsLeft)
    }

    @Test
    fun expiresAfterLastPeriodConsumed() {
        // 0 main + 1x30s; 40s elapsed -> the only period is gone.
        val entry = TimeEntry(thinkingTime = 0.0, periodsLeft = 1, periodTime = 30.0)
        val r = readClock(entry, isCurrentPlayer = true, anchorMs = 0, nowMs = 40_000)
        assertTrue(r.expired)
        assertEquals(0L, r.seconds)
        assertEquals(0, r.periodsLeft)
    }

    // ---- readClock: simple deadline ----

    @Test
    fun simpleDeadlineCountsDownAgainstWallClock() {
        val entry = TimeEntry(thinkingTime = 0.0, moveDeadlineMs = 30_000)
        val r = readClock(entry, isCurrentPlayer = true, anchorMs = 0, nowMs = 18_000)
        assertEquals(12L, r.seconds)
        assertFalse(r.inByoyomi)
    }

    // ---- formatting ----

    @Test
    fun formatsMinutesAndSecondsZeroPadded() {
        assertEquals("5:00", formatClockSeconds(300))
        assertEquals("0:05", formatClockSeconds(5))
        assertEquals("0:00", formatClockSeconds(0))
    }

    @Test
    fun formatsHoursWhenOverAnHour() {
        assertEquals("1:00:00", formatClockSeconds(3600))
        assertEquals("1:23:45", formatClockSeconds(5025))
    }

    @Test
    fun negativeSecondsClampToZero() {
        assertEquals("0:00", formatClockSeconds(-10))
    }

    @Test
    fun appendsByoyomiPeriodCount() {
        val r = ClockReadout(seconds = 25, periodsLeft = 3, inByoyomi = true, expired = false)
        assertEquals("0:25 (3)", formatClock(r))
    }

    @Test
    fun omitsPeriodCountOutsideByoyomi() {
        val r = ClockReadout(seconds = 300, periodsLeft = null, inByoyomi = false, expired = false)
        assertEquals("5:00", formatClock(r))
    }
}
