package com.gobo.app.net

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A single in-game chat line, as shown read-only in the game screen. */
data class ChatMessage(
    val playerId: Int,
    val username: String,
    val body: String,
    /** OGS sends a unix timestamp (seconds); 0 when absent. */
    val date: Long,
)

/**
 * Parse a `game/<id>/chat` socket event into a [ChatMessage]. The event wraps the line
 * under `message` (older payloads use `line`); the inner `body` is normally a string but
 * may instead be an analysis/variation object — those carry no plain text, so we skip
 * them (return null) since the read-only view only renders text. Pure (no socket / Android
 * deps) so the wire-shape handling is unit-tested directly.
 */
fun parseGameChat(data: JsonElement): ChatMessage? {
    val obj = data as? JsonObject ?: return null
    val line = (obj["message"] ?: obj["line"]) as? JsonObject ?: return null
    // Only plain-text bodies are rendered; an object body is a shared variation.
    val bodyPrim = line["body"] as? JsonPrimitive ?: return null
    if (!bodyPrim.isString) return null
    val body = bodyPrim.contentOrNull ?: return null
    return ChatMessage(
        playerId = line["player_id"]?.jsonPrimitive?.intOrNull ?: 0,
        username = line["username"]?.jsonPrimitive?.contentOrNull ?: "",
        body = body,
        date = line["date"]?.jsonPrimitive?.longOrNull ?: 0L,
    )
}
