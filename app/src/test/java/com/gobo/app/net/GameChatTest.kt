package com.gobo.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameChatTest {

    private fun parse(s: String) = parseGameChat(Json.parseToJsonElement(s))

    @Test
    fun parsesAPlainTextLine() {
        val msg = parse(
            """
            {"type":"discussion","message":{
              "username":"opponent","ranking":12,"ui_class":"",
              "player_id":555,"date":1700000000,"body":"good game"}}
            """.trimIndent(),
        )
        assertEquals(555, msg?.playerId)
        assertEquals("opponent", msg?.username)
        assertEquals("good game", msg?.body)
        assertEquals(1700000000L, msg?.date)
    }

    @Test
    fun fallsBackToLineKeyForOlderPayloads() {
        // Some payloads wrap the line under "line" rather than "message".
        val msg = parse("""{"line":{"player_id":1,"username":"me","body":"hi","date":5}}""")
        assertEquals("hi", msg?.body)
        assertEquals(1, msg?.playerId)
    }

    @Test
    fun skipsAnalysisVariationBodies() {
        // A non-string body is a shared variation, which the read-only view can't render.
        val msg = parse(
            """
            {"type":"malkovich","message":{"username":"x","player_id":2,
              "body":{"type":"analysis","from":10,"moves":"aabbcc"}}}
            """.trimIndent(),
        )
        assertNull(msg)
    }

    @Test
    fun appliesDefaultsForMissingFields() {
        val msg = parse("""{"message":{"body":"hello"}}""")
        assertEquals("hello", msg?.body)
        assertEquals(0, msg?.playerId)
        assertEquals("", msg?.username)
        assertEquals(0L, msg?.date)
    }

    @Test
    fun returnsNullWhenNoMessageObject() {
        assertNull(parse("""{"type":"discussion"}"""))
        assertNull(parse("""[]"""))
    }

    @Test
    fun buildsSendPayloadWithoutAuthOrIdentity() {
        // The current protocol authenticates via the socket, so the payload is just the
        // game, text, move number, and channel — no auth/username/ranking fields.
        val msg = buildGameChatMessage(gameId = 42, body = "hi there", moveNumber = 7)
        assertEquals(42L, msg["game_id"]?.jsonPrimitive?.longOrNull)
        assertEquals("hi there", msg["body"]?.jsonPrimitive?.contentOrNull)
        assertEquals(7, msg["move_number"]?.jsonPrimitive?.intOrNull)
        assertEquals("main", msg["type"]?.jsonPrimitive?.contentOrNull) // default channel
        assertNull(msg["auth"])
        assertNull(msg["username"])
    }

    @Test
    fun usesChatIdForIdentityAndCompositeFallback() {
        val withId = parse("""{"message":{"chat_id":"abc123","player_id":7,"body":"hi","date":5}}""")
        assertEquals("abc123", withId?.id)
        // No chat_id -> a stable content composite so re-sends still de-dupe.
        val noId = parse("""{"message":{"player_id":7,"body":"hi","date":5}}""")
        assertEquals("7:5:hi", noId?.id)
    }

    @Test
    fun appendChatIgnoresAlreadyPresentLines() {
        // Regression: OGS re-sends the chat log at game end; appending must be idempotent
        // or the whole history duplicates.
        val hello = ChatMessage(id = "c1", playerId = 7, username = "me", body = "Hello", date = 1)
        val help = ChatMessage(id = "c2", playerId = 7, username = "me", body = "Help", date = 2)

        var chat = appendChat(emptyList(), hello)
        chat = appendChat(chat, help)
        assertEquals(2, chat.size)

        // Re-send of the same two lines (same ids) adds nothing.
        chat = appendChat(chat, hello)
        chat = appendChat(chat, help)
        assertEquals(2, chat.size)
        assertEquals(listOf("Hello", "Help"), chat.map { it.body })
    }
}
