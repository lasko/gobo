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

    @Test
    fun sentChallengesKeepsOnlyMineAndReadsGameFields() {
        // Two challenges: one I sent (challenger 7), one someone else sent to me.
        val body = """
            {"results":[
              {"id":900,"challenger":{"id":7,"username":"me"},
               "game":{"name":"Friendly","width":9,"ranked":true}},
              {"id":901,"challenger":{"id":42,"username":"someone"},
               "game":{"name":"Theirs","width":19,"ranked":false}}
            ]}
        """.trimIndent()
        val mine = parseSentChallenges(body, myPlayerId = 7)
        assertEquals(1, mine.size)
        assertEquals(900L, mine[0].id)
        assertEquals(9, mine[0].boardSize)
        assertTrue(mine[0].ranked)
        assertEquals("Friendly", mine[0].name)
    }

    @Test
    fun sentChallengesAcceptsBareArrayAndChallengerIdField() {
        // Top-level array, and challenger given as a bare id field rather than an object.
        val body = """[{"id":5,"challenger_id":7,"width":13,"ranked":false}]"""
        val mine = parseSentChallenges(body, myPlayerId = 7)
        assertEquals(1, mine.size)
        assertEquals(13, mine[0].boardSize)
        assertEquals("", mine[0].name) // no name -> blank (UI shows a generic label)
    }

    @Test
    fun sentChallengesDefaultsBoardSizeAndIsEmptyWhenNoneMine() {
        assertTrue(parseSentChallenges("""{"results":[]}""", myPlayerId = 7).isEmpty())
        assertTrue(parseSentChallenges("""{"foo":1}""", myPlayerId = 7).isEmpty())
        // A challenge of mine with no width defaults to 19.
        val mine = parseSentChallenges("""[{"id":1,"challenger":{"id":7}}]""", myPlayerId = 7)
        assertEquals(19, mine[0].boardSize)
    }
}
