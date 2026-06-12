package com.gobo.app.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** A live game shown in the spectate list. */
data class LiveGame(
    val id: Long,
    val blackName: String,
    val whiteName: String,
    val width: Int,
    val height: Int,
    /** Moves played so far — a rough sense of how far along the game is. */
    val moveNumber: Int,
)

/**
 * Build the `gamelist/query` payload for the live-games list. `sort_by:"rank"` is the only sort
 * OGS documents; an empty `where` returns all public live games. Pure (no socket deps) so the
 * request shape is unit-tested directly.
 */
fun buildGameListQuery(from: Int = 0, limit: Int = 50): JsonObject = buildJsonObject {
    put("list", "live")
    put("sort_by", "rank")
    putJsonObject("where") {}
    put("from", from)
    put("limit", limit)
}

/**
 * Parse a `gamelist/query` reply into the games to display. The reply wraps the games under
 * `results`; each entry carries `id`, `black`/`white` player objects, board `width`/`height`, and
 * `move_number`. Skips entries without an id. Pure, for unit testing.
 */
fun parseGameList(data: JsonElement): List<LiveGame> {
    val results = (data as? JsonObject)?.get("results") as? JsonArray ?: return emptyList()
    return results.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val id = o["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
        LiveGame(
            id = id,
            blackName = o["black"]?.jsonObject?.get("username")?.jsonPrimitive?.contentOrNull ?: "Black",
            whiteName = o["white"]?.jsonObject?.get("username")?.jsonPrimitive?.contentOrNull ?: "White",
            width = o["width"]?.jsonPrimitive?.intOrNull ?: 19,
            height = o["height"]?.jsonPrimitive?.intOrNull ?: 19,
            moveNumber = o["move_number"]?.jsonPrimitive?.intOrNull ?: 0,
        )
    }
}
