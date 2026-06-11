package com.gobo.app.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OgsCoordTest {

    @Test
    fun encodesOrigin() = assertEquals("aa", OgsCoord.encode(0, 0))

    @Test
    fun encodesInteriorPoint() = assertEquals("qd", OgsCoord.encode(16, 3))

    @Test
    fun encodesPassForNegativeCoords() = assertEquals("..", OgsCoord.encode(-1, -1))

    @Test
    fun decodesInteriorPoint() = assertEquals(16 to 3, OgsCoord.decode("qd"))

    @Test
    fun decodesPassToNull() = assertNull(OgsCoord.decode(".."))

    @Test
    fun decodesTooShortToNull() = assertNull(OgsCoord.decode("a"))

    @Test
    fun roundTrips() {
        for (x in 0 until 19) for (y in 0 until 19) {
            assertEquals(x to y, OgsCoord.decode(OgsCoord.encode(x, y)))
        }
    }
}
