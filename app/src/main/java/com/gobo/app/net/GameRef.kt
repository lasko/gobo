package com.gobo.app.net

/**
 * Extract an OGS game id from user input for spectating: either a bare number (`12345`) or an
 * online-go.com game URL (`https://online-go.com/game/12345`, with or without a trailing slash or
 * query). Returns null when no positive id is found, so the Watch button can stay disabled on junk.
 * Pure (no Android deps) so the input handling is unit-tested directly.
 */
fun parseGameId(input: String): Long? {
    val s = input.trim()
    s.toLongOrNull()?.let { return it.takeIf { v -> v > 0 } }
    return Regex("""/game/(\d+)""").find(s)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }
}
