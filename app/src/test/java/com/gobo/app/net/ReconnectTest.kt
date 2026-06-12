package com.gobo.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectTest {

    @Test
    fun firstAttemptIsImmediateFixedDelay() {
        // The first range is [50, 50], so jitter can't change it.
        assertEquals(50L, reconnectDelayMs(0, random = 0.0))
        assertEquals(50L, reconnectDelayMs(0, random = 0.999))
    }

    @Test
    fun jitterSpansTheStepRange() {
        // Second step is [100, 300]: low end at random 0, near the top at random ~1.
        assertEquals(100L, reconnectDelayMs(1, random = 0.0))
        assertEquals(299L, reconnectDelayMs(1, random = 0.999))
        // Midpoint lands inside the range.
        val mid = reconnectDelayMs(1, random = 0.5)
        assertTrue("$mid in 100..300", mid in 100L..300L)
    }

    @Test
    fun laterAttemptsBackOffToTheCap() {
        // Attempts past the ladder clamp to the last range [2000, 5000].
        val capLo = reconnectDelayMs(10, random = 0.0)
        val capHi = reconnectDelayMs(10, random = 0.999)
        assertEquals(2_000L, capLo)
        assertTrue("$capHi <= 5000", capHi in 2_000L..5_000L)
        // The ladder is monotonic non-decreasing at the low (jitter=0) end.
        val lows = (0..6).map { reconnectDelayMs(it, random = 0.0) }
        assertEquals(lows, lows.sorted())
    }

    @Test
    fun clampsOutOfRangeRandom() {
        // Defensive: a random outside [0,1) still yields a delay within the step's bounds.
        assertEquals(100L, reconnectDelayMs(1, random = -1.0))
        assertEquals(300L, reconnectDelayMs(1, random = 1.0))
    }
}
