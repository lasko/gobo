package com.gobo.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun rendersTranslatedBotBodies() {
        // Regression: bots send greetings/end-notes as a translated body OBJECT, not a string
        // (captured verbatim from a live amybot game). We previously dropped these as if they
        // were analysis variations, so no bot message ever appeared. Render the `en` text.
        val msg = parse(
            """
            {"channel":"main","line":{"player_id":605979,"username":"amybot-beginner",
              "date":1781275273,"chat_id":"c25","move_number":null,
              "body":{"en":"Good luck, have fun!","type":"translated"}}}
            """.trimIndent(),
        )
        assertEquals("Good luck, have fun!", msg?.body)
        assertEquals(605979, msg?.playerId)
        assertEquals("amybot-beginner", msg?.username)
        assertEquals("c25", msg?.id)
    }

    @Test
    fun skipsTranslatedBodyMissingEnglishText() {
        // A translated body must actually carry `en`; otherwise there's nothing to show.
        assertNull(parse("""{"message":{"player_id":1,"body":{"type":"translated"}}}"""))
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
    fun parsesChatLogFromGamedataSnapshot() {
        // History a late-connecting client would otherwise miss (e.g. a bot's greeting).
        val gamedata = Json.parseToJsonElement(
            """
            {"phase":"play","chat_log":[
              {"chat_id":"g1","player_id":99,"username":"bot","body":"Good luck!","date":1},
              {"chat_id":"g2","player_id":99,"username":"bot","body":{"type":"analysis"},"date":2}
            ]}
            """.trimIndent(),
        )
        val log = parseGameChatLog(gamedata)
        assertEquals(1, log.size) // the analysis-body line is skipped
        assertEquals("Good luck!", log[0].body)
        assertEquals("g1", log[0].id)
    }

    @Test
    fun chatLogIsEmptyWhenFieldAbsent() {
        assertTrue(parseGameChatLog(Json.parseToJsonElement("""{"phase":"play"}""")).isEmpty())
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
