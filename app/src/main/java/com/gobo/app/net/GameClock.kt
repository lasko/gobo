package com.gobo.app.net

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.ceil

/**
 * One player's clock state as of the anchor (`last_move`) instant. Only the side to move
 * ticks down; the other side stays frozen at these values. Mirrors the OGS clock entry,
 * whose shape depends on the time-control system:
 *   byoyomi  -> { thinking_time, periods, period_time, period_time_left? }
 *   fischer  -> { thinking_time, skip_bonus }   (no periods; the increment is already
 *                baked into thinking_time by the server on each move, so we never add it)
 *   absolute -> { thinking_time }
 *   simple   -> a bare number = the absolute move deadline (see [moveDeadlineMs])
 */
data class TimeEntry(
    /** Main thinking time remaining, in seconds, as of the anchor. */
    val thinkingTime: Double,
    /** Byoyomi periods remaining (incl. the current one); null for non-byoyomi systems. */
    val periodsLeft: Int? = null,
    /** Length of one byoyomi period, in seconds; null for non-byoyomi systems. */
    val periodTime: Double? = null,
    /** Seconds left in the current byoyomi period when the snapshot is mid-period; null when
     *  a fresh turn starts a full period. */
    val periodTimeLeft: Double? = null,
    /** "simple" time control: absolute epoch-ms the current move must be made by; null
     *  otherwise. Counts down against wall-clock rather than a [thinkingTime] budget. */
    val moveDeadlineMs: Long? = null,
)

/**
 * A full clock snapshot from a `game/<id>/clock` event or the gamedata `clock` object.
 * [lastMoveMs] is the anchor the live countdown subtracts elapsed wall-time from, and only
 * the [currentPlayerId]'s side advances. [skewMs] aligns the device clock to the server's
 * when the event carries a `now` field (0 when it doesn't, falling back to the device clock).
 */
data class GameClock(
    val currentPlayerId: Int,
    val blackPlayerId: Int,
    val whitePlayerId: Int,
    val lastMoveMs: Long,
    val skewMs: Long,
    val black: TimeEntry,
    val white: TimeEntry,
)

/** A computed, displayable clock value for one player at a given wall-clock instant. */
data class ClockReadout(
    /** Whole seconds left in the active component (main time, or the current byoyomi period). */
    val seconds: Long,
    /** Byoyomi periods remaining once main time is gone; null when the system has no periods. */
    val periodsLeft: Int?,
    /** True once main time is exhausted and the player is counting byoyomi periods. */
    val inByoyomi: Boolean,
    /** True when the player has no time left (would lose on time). */
    val expired: Boolean,
)

/**
 * Parse one `black_time`/`white_time` value. The object form covers byoyomi/fischer/absolute;
 * a bare number is "simple" time — a large value is an absolute epoch deadline (normalized to
 * ms), a small one a plain seconds-remaining budget. Returns null for an unparseable element.
 */
fun parseTimeEntry(el: JsonElement?): TimeEntry? = when (el) {
    is JsonObject -> TimeEntry(
        thinkingTime = el["thinking_time"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        periodsLeft = el["periods"]?.jsonPrimitive?.intOrNull,
        periodTime = el["period_time"]?.jsonPrimitive?.doubleOrNull,
        periodTimeLeft = el["period_time_left"]?.jsonPrimitive?.doubleOrNull,
    )
    is JsonPrimitive -> el.doubleOrNull?.let { v ->
        if (v > 1_000_000_000.0) {
            // Looks like an absolute epoch; treat >1e12 as ms, else seconds -> ms.
            val ms = if (v > 1_000_000_000_000.0) v.toLong() else (v * 1000).toLong()
            TimeEntry(thinkingTime = 0.0, moveDeadlineMs = ms)
        } else {
            TimeEntry(thinkingTime = v)
        }
    }
    else -> null
}

/**
 * Parse a `clock` object (from a `game/<id>/clock` event or the gamedata snapshot) into a
 * [GameClock]. [deviceNowMs] is the device clock at parse time, used only to derive [GameClock.skewMs]
 * from the server's optional `now` field. Pure (the caller supplies the clock) so it's unit-tested
 * directly. Returns null without a `current_player` or parseable per-player times.
 */
fun parseClock(data: JsonElement, deviceNowMs: Long): GameClock? {
    val obj = data as? JsonObject ?: return null
    val current = obj["current_player"]?.jsonPrimitive?.intOrNull ?: return null
    val black = parseTimeEntry(obj["black_time"]) ?: return null
    val white = parseTimeEntry(obj["white_time"]) ?: return null
    val serverNow = obj["now"]?.jsonPrimitive?.longOrNull
    return GameClock(
        currentPlayerId = current,
        blackPlayerId = obj["black_player_id"]?.jsonPrimitive?.intOrNull ?: 0,
        whitePlayerId = obj["white_player_id"]?.jsonPrimitive?.intOrNull ?: 0,
        lastMoveMs = obj["last_move"]?.jsonPrimitive?.longOrNull ?: 0L,
        skewMs = if (serverNow != null) serverNow - deviceNowMs else 0L,
        black = black,
        white = white,
    )
}

/**
 * Compute a player's live [ClockReadout] at server-time [nowMs] (device clock already
 * skew-adjusted by the caller). Only [isCurrentPlayer] advances; the opponent is frozen at the
 * snapshot. Main time counts down first; once exhausted, byoyomi periods are consumed one at a
 * time and the current period's remaining seconds are shown. Fischer/absolute (no periods) simply
 * expire at zero. Pure, so the countdown maths is unit-tested directly.
 */
fun readClock(entry: TimeEntry, isCurrentPlayer: Boolean, anchorMs: Long, nowMs: Long): ClockReadout {
    // "simple": a fixed move deadline, counted against wall-clock for the side to move.
    entry.moveDeadlineMs?.let { deadline ->
        val left = (deadline - nowMs).coerceAtLeast(0L) / 1000.0
        return ClockReadout(ceil(left).toLong(), periodsLeft = null, inByoyomi = false, expired = left <= 0)
    }

    val elapsed = if (isCurrentPlayer) (nowMs - anchorMs).coerceAtLeast(0L) / 1000.0 else 0.0
    val mainLeft = entry.thinkingTime - elapsed
    val periodTime = entry.periodTime
    if (mainLeft > 0 || entry.periodsLeft == null || periodTime == null || periodTime <= 0) {
        // Main time still running, or a system without byoyomi periods.
        return ClockReadout(
            seconds = ceil(mainLeft.coerceAtLeast(0.0)).toLong(),
            periodsLeft = null,
            inByoyomi = false,
            expired = mainLeft <= 0 && entry.periodsLeft == null,
        )
    }

    // Into byoyomi: spend the (possibly partial) current period, then consume whole periods.
    var periodsLeft = entry.periodsLeft
    var currentLeft = (entry.periodTimeLeft ?: periodTime) - (-mainLeft)
    while (currentLeft <= 0 && periodsLeft > 1) {
        periodsLeft -= 1
        currentLeft = periodTime - (-currentLeft) // carry the excess into the next full period
    }
    return if (currentLeft <= 0) {
        ClockReadout(seconds = 0, periodsLeft = 0, inByoyomi = true, expired = true)
    } else {
        ClockReadout(ceil(currentLeft).toLong(), periodsLeft, inByoyomi = true, expired = false)
    }
}

/** Format whole seconds as `H:MM:SS` (when ≥ 1h) or `M:SS`, zero-padded for a stable width. */
fun formatClockSeconds(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Full display string; appends the byoyomi period count once main time is gone, e.g. `0:25 (3)`. */
fun formatClock(readout: ClockReadout): String {
    val base = formatClockSeconds(readout.seconds)
    return if (readout.inByoyomi && readout.periodsLeft != null) "$base (${readout.periodsLeft})" else base
}
