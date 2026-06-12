package com.gobo.app.net

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A single in-game chat line, as shown in the game screen. */
data class ChatMessage(
    /** Stable line identity for de-duplication — OGS' `chat_id`, or a content composite
     *  when absent. The server re-sends the chat log on some lifecycle events, so the same
     *  line can arrive twice; [appendChat] uses this to ignore the repeat. */
    val id: String,
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
 * them (return null) since the view only renders text. Pure (no socket / Android deps) so
 * the wire-shape handling is unit-tested directly.
 */
fun parseGameChat(data: JsonElement): ChatMessage? {
    val obj = data as? JsonObject ?: return null
    val line = (obj["message"] ?: obj["line"]) as? JsonObject ?: return null
    // Only plain-text bodies are rendered; an object body is a shared variation.
    val bodyPrim = line["body"] as? JsonPrimitive ?: return null
    if (!bodyPrim.isString) return null
    val body = bodyPrim.contentOrNull ?: return null
    val playerId = line["player_id"]?.jsonPrimitive?.intOrNull ?: 0
    val date = line["date"]?.jsonPrimitive?.longOrNull ?: 0L
    return ChatMessage(
        // Prefer the server's chat_id; fall back to a content composite so a re-sent
        // identical line still de-dupes when no id is present.
        id = line["chat_id"]?.jsonPrimitive?.contentOrNull ?: "$playerId:$date:$body",
        playerId = playerId,
        username = line["username"]?.jsonPrimitive?.contentOrNull ?: "",
        body = body,
        date = date,
    )
}

/**
 * Append [message] to [existing] unless a line with the same [ChatMessage.id] is already
 * present. OGS re-sends the chat log on some lifecycle events (e.g. when a game finishes),
 * so chat appends must be idempotent or the whole history duplicates.
 */
fun appendChat(existing: List<ChatMessage>, message: ChatMessage): List<ChatMessage> =
    if (existing.any { it.id == message.id }) existing else existing + message

/**
 * Build the `game/chat` send payload. The current OGS protocol authenticates the sender
 * via the earlier `authenticate` message, so a chat line needs no per-message auth or
 * identity (the legacy `game_chat_auth`/`username`/`ranking`/`ui_class` fields are gone) —
 * only the game, the text, the move it's attached to, and the channel [type] ("main" for
 * normal in-game play). Pure, so the shape is unit-tested directly.
 */
fun buildGameChatMessage(
    gameId: Long,
    body: String,
    moveNumber: Int,
    type: String = "main",
): JsonObject = buildJsonObject {
    put("game_id", gameId)
    put("body", body)
    put("move_number", moveNumber)
    put("type", type)
}
