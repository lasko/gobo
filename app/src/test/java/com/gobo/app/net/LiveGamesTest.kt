package com.gobo.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveGamesTest {

    @Test
    fun buildsQueryShape() {
        val q = buildGameListQuery(from = 0, limit = 50)
        assertEquals("live", q["list"]?.jsonPrimitive?.contentOrNull)
        assertEquals("rank", q["sort_by"]?.jsonPrimitive?.contentOrNull)
        assertEquals(0, q["from"]?.jsonPrimitive?.intOrNull)
        assertEquals(50, q["limit"]?.jsonPrimitive?.intOrNull)
        assertTrue("where present", q["where"]?.jsonObject != null)
    }

    private fun parse(s: String) = parseGameList(Json.parseToJsonElement(s))

    @Test
    fun parsesResults() {
        val games = parse(
            """
            {"list":"live","results":[
              {"id":111,"width":19,"height":19,"move_number":84,
               "black":{"username":"alice","id":1},"white":{"username":"bob","id":2}},
              {"id":222,"width":13,"height":13,"move_number":12,
               "black":{"username":"carol","id":3},"white":{"username":"dave","id":4}}
            ]}
            """.trimIndent(),
        )
        assertEquals(2, games.size)
        assertEquals(111L, games[0].id)
        assertEquals("alice", games[0].blackName)
        assertEquals("bob", games[0].whiteName)
        assertEquals(19, games[0].width)
        assertEquals(84, games[0].moveNumber)
        assertEquals(13, games[1].height)
    }

    @Test
    fun appliesDefaultsAndSkipsEntriesWithoutId() {
        val games = parse(
            """
            {"results":[
              {"width":9,"height":9},
              {"id":333}
            ]}
            """.trimIndent(),
        )
        // First entry has no id -> skipped; second uses defaults.
        assertEquals(1, games.size)
        assertEquals(333L, games[0].id)
        assertEquals("Black", games[0].blackName)
        assertEquals("White", games[0].whiteName)
        assertEquals(19, games[0].width)
        assertEquals(0, games[0].moveNumber)
    }

    @Test
    fun emptyWhenNoResults() {
        assertTrue(parse("""{"list":"live"}""").isEmpty())
        assertTrue(parseGameList(Json.parseToJsonElement("""[]""")).isEmpty())
    }
}
