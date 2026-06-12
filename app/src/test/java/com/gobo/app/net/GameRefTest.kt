package com.gobo.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameRefTest {

    @Test
    fun parsesBareNumber() {
        assertEquals(12345L, parseGameId("12345"))
        assertEquals(99L, parseGameId("  99  ")) // trimmed
    }

    @Test
    fun parsesGameUrl() {
        assertEquals(12345L, parseGameId("https://online-go.com/game/12345"))
        assertEquals(12345L, parseGameId("https://online-go.com/game/12345/")) // trailing slash
        assertEquals(12345L, parseGameId("online-go.com/game/12345?foo=bar")) // query
        assertEquals(777L, parseGameId("http://online-go.com/game/777"))
    }

    @Test
    fun rejectsJunkAndNonPositive() {
        assertNull(parseGameId("abc"))
        assertNull(parseGameId(""))
        assertNull(parseGameId("0"))
        assertNull(parseGameId("-5"))
        assertNull(parseGameId("12345abc")) // not a clean id, no /game/ segment
    }
}
