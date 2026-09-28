package com.swims.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class GoalCalculatorTest {

    @Test
    fun testNormalValues() {
        // base = 70 * 35 = 2450. age 25 (0), activity 0 (0) -> 2450
        assertEquals(2450, GoalCalculator.calculate(70f, 25, 0))
    }

    @Test
    fun testAgeAdjustments() {
        // base = 70 * 35 = 2450. age 15 (-100) -> 2350
        assertEquals(2350, GoalCalculator.calculate(70f, 15, 0))
        // age 60 (+200) -> 2650
        assertEquals(2650, GoalCalculator.calculate(70f, 60, 0))
    }

    @Test
    fun testActivityAdjustments() {
        // base = 70 * 35 = 2450. activity 1 (+300) -> 2750
        assertEquals(2750, GoalCalculator.calculate(70f, 25, 1))
        // activity 2 (+500) -> 2950
        assertEquals(2950, GoalCalculator.calculate(70f, 25, 2))
        // activity 3 (+750) -> 3200
        assertEquals(3200, GoalCalculator.calculate(70f, 25, 3))
    }

    @Test
    fun testLowerClamp() {
        // base = 40 * 35 = 1400. < 1500 -> 1500
        assertEquals(1500, GoalCalculator.calculate(40f, 25, 0))
    }

    @Test
    fun testUpperClamp() {
        // base = 150 * 35 = 5250. > 5000 -> 5000
        assertEquals(5000, GoalCalculator.calculate(150f, 25, 3))
    }
}
