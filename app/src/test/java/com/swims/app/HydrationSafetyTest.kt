package com.swims.app.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HydrationSafetyTest {

    private val goal = 3300

    @Test
    fun normalDayIsNotFlagged() {
        val r = HydrationSafety.check(todayTotalMl = 1800, goalMl = goal, lastHourMl = 250)
        assertEquals(HydrationSafety.Level.NORMAL, r.level)
        assertFalse(r.isWarning)
    }

    @Test
    fun meetingTheGoalIsNotFlagged() {
        val r = HydrationSafety.check(todayTotalMl = goal, goalMl = goal, lastHourMl = 300)
        assertEquals(HydrationSafety.Level.NORMAL, r.level)
    }

    /** Slightly over goal is normal and healthy — it must not nag. */
    @Test
    fun modestOvershootIsNotFlagged() {
        val r = HydrationSafety.check(todayTotalMl = 3800, goalMl = goal, lastHourMl = 300)
        assertEquals(HydrationSafety.Level.NORMAL, r.level)
    }

    @Test
    fun wellOverGoalRaisesCaution() {
        val r = HydrationSafety.check(todayTotalMl = 4200, goalMl = goal, lastHourMl = 200)
        assertEquals(HydrationSafety.Level.CAUTION, r.level)
        assertNotNull(r.message)
        assertTrue(r.message!!.contains("900"))   // 4200 − 3300 past the goal
    }

    @Test
    fun farOverGoalRaisesHigh() {
        val r = HydrationSafety.check(todayTotalMl = 6500, goalMl = goal, lastHourMl = 200)
        assertEquals(HydrationSafety.Level.HIGH, r.level)
        assertNotNull(r.message)
    }

    /** Drinking a litre in one hour is flagged even on a low day total. */
    @Test
    fun fastIntakeIsFlaggedRegardlessOfDailyTotal() {
        val r = HydrationSafety.check(todayTotalMl = 1200, goalMl = goal, lastHourMl = 1200)
        assertEquals(HydrationSafety.Level.CAUTION, r.level)
        assertTrue(r.message!!.contains("last hour"))
    }

    @Test
    fun justUnderTheHourlyLimitIsNotFlagged() {
        val r = HydrationSafety.check(todayTotalMl = 1200, goalMl = goal, lastHourMl = 999)
        assertEquals(HydrationSafety.Level.NORMAL, r.level)
    }

    /**
     * A high-requirement user whose goal is genuinely large is never warned on
     * the daily rule while still below that goal.
     */
    @Test
    fun highGoalUserBelowGoalIsNotFlagged() {
        val r = HydrationSafety.check(todayTotalMl = 4500, goalMl = 5000, lastHourMl = 200)
        assertEquals(HydrationSafety.Level.NORMAL, r.level)
    }

    @Test
    fun thresholdBoundariesBehave() {
        // Exactly at the caution threshold, and above goal → caution.
        assertEquals(
            HydrationSafety.Level.CAUTION,
            HydrationSafety.check(HydrationSafety.DAILY_CAUTION_ML, goal, 0).level
        )
        // Exactly at the high threshold → high.
        assertEquals(
            HydrationSafety.Level.HIGH,
            HydrationSafety.check(HydrationSafety.DAILY_HIGH_ML, goal, 0).level
        )
        // Exactly at the hourly threshold → caution.
        assertEquals(
            HydrationSafety.Level.CAUTION,
            HydrationSafety.check(500, goal, HydrationSafety.HOURLY_CAUTION_ML).level
        )
    }
}
