package com.gobo.app.net

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Build the `game/undo/request` payload. The current protocol authenticates via the earlier
 * `authenticate` message, so it's just the game and [moveNumber] — the current move count, the
 * point to take back to (matching goban's `requestUndo`, which sends `getCurrentMoveNumber()`).
 * Pure (no socket / Android deps) so the shape is unit-tested directly.
 */
fun buildUndoRequest(gameId: Long, moveNumber: Int): JsonObject = buildJsonObject {
    put("game_id", gameId)
    put("move_number", moveNumber)
}

/**
 * How many moves an `undo_accepted` (or `undo_requested`) payload takes back. OGS sends either a
 * bare number/string (legacy — a single move) or the modern object
 * `{move_number, undo_move_count, requested_by}`. Returns `undo_move_count` when the object gives
 * a positive one, else **1** — our request only ever takes back the last move, so a single move is
 * the right default for the legacy/absent cases. Pure, for unit testing.
 */
fun parseUndoMoveCount(data: JsonElement): Int {
    val obj = data as? JsonObject ?: return 1
    val count = obj["undo_move_count"]?.jsonPrimitive?.intOrNull ?: return 1
    return if (count > 0) count else 1
}
