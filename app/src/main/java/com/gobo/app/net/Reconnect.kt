package com.gobo.app.net

/**
 * Realtime socket connection lifecycle, surfaced so the UI can tell the player when the board is
 * frozen because we're re-establishing the connection rather than waiting on the opponent.
 */
enum class ConnectionState { Connecting, Connected, Reconnecting, Disconnected }

/**
 * Backoff ladder of (min, max) millisecond ranges per reconnect attempt. Starts fast — most drops
 * (device sleep, wifi↔cellular handoff) recover in well under a second — then backs off toward a
 * few-second cap for a prolonged outage so we don't spin the radio. (goban retries every ≤750ms
 * forever; we add longer steps to be gentler on a phone's battery.) Attempts past the ladder reuse
 * the last range.
 */
private val RECONNECT_LADDER = listOf(
    50L to 50L,
    100L to 300L,
    250L to 750L,
    1_000L to 2_000L,
    2_000L to 5_000L,
)

/**
 * Delay before reconnect [attempt] (0-based), randomly jittered within that step's range to spread
 * a reconnect surge after a server blip. [random] (a value in `[0, 1)`) is injectable so the schedule
 * is unit-tested deterministically. Pure (no socket / Android deps).
 */
fun reconnectDelayMs(attempt: Int, random: Double = Math.random()): Long {
    val (lo, hi) = RECONNECT_LADDER[attempt.coerceIn(0, RECONNECT_LADDER.size - 1)]
    return lo + (random.coerceIn(0.0, 1.0) * (hi - lo)).toLong()
}
