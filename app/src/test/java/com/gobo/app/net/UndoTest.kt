package com.gobo.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UndoTest {

    @Test
    fun buildsUndoRequestPayload() {
        // The socket already authenticates us, so the payload is just the game and the move
        // number to take back to — no auth/identity fields.
        val msg = buildUndoRequest(gameId = 42, moveNumber = 7)
        assertEquals(42L, msg["game_id"]?.jsonPrimitive?.longOrNull)
        assertEquals(7, msg["move_number"]?.jsonPrimitive?.intOrNull)
        assertNull(msg["player_id"])
        assertNull(msg["auth"])
    }

    private fun count(s: String) = parseUndoMoveCount(Json.parseToJsonElement(s))

    @Test
    fun usesUndoMoveCountFromObjectPayload() {
        assertEquals(2, count("""{"move_number":10,"undo_move_count":2,"requested_by":5}"""))
    }

    @Test
    fun defaultsToOneWhenObjectOmitsCount() {
        // Modern object payload without an explicit count = a single move.
        assertEquals(1, count("""{"move_number":10,"requested_by":5}"""))
    }

    @Test
    fun defaultsToOneForNonPositiveCount() {
        assertEquals(1, count("""{"undo_move_count":0}"""))
    }

    @Test
    fun defaultsToOneForLegacyBareNumber() {
        // Legacy payload is a bare move number, which implies a single move.
        assertEquals(1, count("9"))
    }
}
