package com.gobo.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OgsParseTest {

    @Test
    fun uiConfigReadsJwtAndUser() {
        val body = """
            {"user_jwt":"jwt-123","user":{"id":42,"username":"lasko"}}
        """.trimIndent()
        val cfg = parseUiConfig(body)
        assertEquals("jwt-123", cfg.userJwt)
        assertEquals(42, cfg.playerId)
        assertEquals("lasko", cfg.username)
    }

    @Test
    fun uiConfigDefaultsWhenUserMissing() {
        val cfg = parseUiConfig("""{"user_jwt":"only-jwt"}""")
        assertEquals("only-jwt", cfg.userJwt)
        assertEquals(0, cfg.playerId)
        assertEquals("", cfg.username)
    }

    @Test(expected = IllegalStateException::class)
    fun uiConfigThrowsWithoutJwt() {
        // The socket can't authenticate without user_jwt, so a missing one must fail loudly.
        parseUiConfig("""{"user":{"id":1}}""")
    }

    @Test
    fun activeGamesFlagsMyTurnFromCurrentPlayer() {
        val body = """
            {"active_games":[
              {"id":1001,"width":13,
               "players":{"black":{"username":"me"},"white":{"username":"bot"}},
               "json":{"clock":{"current_player":7}}},
              {"id":1002,"width":19,
               "players":{"black":{"username":"bot"},"white":{"username":"me"}},
               "json":{"clock":{"current_player":99}}}
            ]}
        """.trimIndent()
        val games = parseActiveGames(body, myPlayerId = 7)

        assertEquals(2, games.size)
        assertEquals(1001L, games[0].id)
        assertEquals(13, games[0].boardSize)
        assertEquals("me", games[0].blackUsername)
        assertEquals("bot", games[0].whiteUsername)
        assertTrue(games[0].myTurn)        // current_player == 7
        assertEquals(false, games[1].myTurn) // current_player == 99
    }

    @Test
    fun activeGamesAppliesDefaultsForMissingFields() {
        val body = """{"active_games":[{"id":2002}]}"""
        val games = parseActiveGames(body, myPlayerId = 1)
        assertEquals(1, games.size)
        assertEquals(19, games[0].boardSize)          // default size
        assertEquals("Black", games[0].blackUsername) // default names
        assertEquals("White", games[0].whiteUsername)
        assertEquals(false, games[0].myTurn)          // current_player defaults to 0
    }

    @Test
    fun activeGamesIsEmptyWhenFieldAbsent() {
        assertTrue(parseActiveGames("""{"foo":1}""", myPlayerId = 1).isEmpty())
    }

    @Test
    fun gameResultReadsOutcomeAndWinner() {
        val r = parseGameResult("""{"outcome":"Resignation","winner":7}""")
        assertEquals("Resignation", r.outcome)
        assertEquals(7, r.winnerId)
    }

    @Test
    fun gameResultDefaultsWhenFieldsAbsent() {
        val r = parseGameResult("""{}""")
        assertEquals("", r.outcome)
        assertNull(r.winnerId)
    }
}
