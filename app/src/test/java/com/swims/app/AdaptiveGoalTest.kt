package com.swims.app.ml

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveGoalTest {

    @Test
    fun testColdStart() {
        val history = listOf(
            DayRecord("2026-09-10", 2500),
            DayRecord("2026-09-11", 2600)
        )
        assertEquals(2450, AdaptiveGoalEngine.refine(2450, history))
    }

    @Test
    fun test7PlusDays() {
        val history = List(7) { DayRecord("2026-09-01", 2600) }
        assertEquals(2600, AdaptiveGoalEngine.refine(2450, history))
    }

    @Test
    fun testTwentyPercentConstraint() {
        val history = List(10) { DayRecord("2026-09-01", 5000) }
        // base = 2000, max drift 20% -> 2400
        assertEquals(2400, AdaptiveGoalEngine.refine(2000, history))
    }

    @Test
    fun testLimitsClamp() {
        val historyLow = List(10) { DayRecord("2026-09-01", 500) }
        // base = 1600. -20% is 1280, but min clamp is 1500
        assertEquals(1500, AdaptiveGoalEngine.refine(1600, historyLow))

        val historyHigh = List(10) { DayRecord("2026-09-01", 6000) }
        // base = 4900. +20% is 5880, but max clamp is 5000
        assertEquals(5000, AdaptiveGoalEngine.refine(4900, historyHigh))
    }
}
