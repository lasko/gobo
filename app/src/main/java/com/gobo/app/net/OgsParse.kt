package com.gobo.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Pure parsers for OGS REST response bodies. Kept as top-level functions (no OkHttp /
 * Android deps) so the JSON shape handling — the part most likely to drift when OGS
 * changes a payload — can be unit-tested directly. [OgsRest] owns the HTTP; these own
 * the decoding.
 */
private val parseJson = Json { ignoreUnknownKeys = true }

/** Parse `/api/v1/ui/config`. Throws if the body lacks the `user_jwt` the socket needs. */
fun parseUiConfig(body: String): UiConfig {
    val root = parseJson.parseToJsonElement(body).jsonObject
    val jwt = root["user_jwt"]?.jsonPrimitive?.contentOrNull
        ?: error("no user_jwt in config")
    val user = root["user"]?.jsonObject ?: JsonObject(emptyMap())
    return UiConfig(
        userJwt = jwt,
        playerId = user["id"]?.jsonPrimitive?.intOrNull ?: 0,
        username = user["username"]?.jsonPrimitive?.contentOrNull ?: "",
    )
}

/**
 * Parse the `active_games` array from the `/api/v1/ui/overview` body into game summaries.
 * [myPlayerId] decides each game's [GameSummary.myTurn] by comparing against the game's
 * `clock.current_player`. Returns an empty list when the field is absent or malformed.
 */
fun parseActiveGames(body: String, myPlayerId: Int): List<GameSummary> {
    val root = parseJson.parseToJsonElement(body).jsonObject
    val games = root["active_games"]?.jsonArray ?: return emptyList()
    return games.mapNotNull { el ->
        val g = el.jsonObject
        val id = g["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
        val size = g["width"]?.jsonPrimitive?.intOrNull ?: 19
        val players = g["players"]?.jsonObject
        val blackName = players?.get("black")?.jsonObject?.get("username")?.jsonPrimitive?.contentOrNull ?: "Black"
        val whiteName = players?.get("white")?.jsonObject?.get("username")?.jsonPrimitive?.contentOrNull ?: "White"
        // clock.current_player holds the player id whose turn it is.
        val currentPlayer = g["json"]?.jsonObject
            ?.get("clock")?.jsonObject
            ?.get("current_player")?.jsonPrimitive?.intOrNull ?: 0
        GameSummary(id, size, blackName, whiteName, myTurn = currentPlayer == myPlayerId)
    }
}

/** Parse a finished game's authoritative result from `/api/v1/games/{id}`. */
fun parseGameResult(body: String): GameResult {
    val root = parseJson.parseToJsonElement(body).jsonObject
    return GameResult(
        outcome = root["outcome"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        winnerId = root["winner"]?.jsonPrimitive?.intOrNull,
    )
}

/**
 * Parse `/api/v1/me/challenges` into the challenges the local player *sent* and are still
 * pending. The endpoint returns challenges in both directions, so we keep only those whose
 * `challenger` is [myPlayerId]. The list may arrive bare or wrapped under `results`/
 * `challenges`, and the board size / ranked / name may sit on a nested `game` object or at
 * the top level — accept either, and default leniently.
 */
fun parseSentChallenges(body: String, myPlayerId: Int): List<PendingChallenge> {
    val root = parseJson.parseToJsonElement(body)
    val arr = when (root) {
        is JsonArray -> root
        is JsonObject -> (root["results"] ?: root["challenges"]) as? JsonArray ?: return emptyList()
        else -> return emptyList()
    }
    return arr.mapNotNull { el ->
        val c = el as? JsonObject ?: return@mapNotNull null
        val id = c["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
        // `challenger` may be a nested player object or a bare id field; keep only mine.
        val challengerId = (c["challenger"] as? JsonObject)?.get("id")?.jsonPrimitive?.intOrNull
            ?: c["challenger_id"]?.jsonPrimitive?.intOrNull
        if (challengerId != myPlayerId) return@mapNotNull null
        val game = c["game"] as? JsonObject
        PendingChallenge(
            id = id,
            boardSize = game?.get("width")?.jsonPrimitive?.intOrNull
                ?: c["width"]?.jsonPrimitive?.intOrNull ?: 19,
            ranked = game?.get("ranked")?.jsonPrimitive?.booleanOrNull
                ?: c["ranked"]?.jsonPrimitive?.booleanOrNull ?: false,
            name = game?.get("name")?.jsonPrimitive?.contentOrNull
                ?: c["name"]?.jsonPrimitive?.contentOrNull ?: "",
        )
    }
}
