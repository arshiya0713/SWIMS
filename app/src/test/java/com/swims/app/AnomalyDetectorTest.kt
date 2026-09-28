package com.swims.app.ml

import org.junit.Assert.assertEquals
import org.junit.Test

class AnomalyDetectorTest {

    @Test
    fun testInsufficientData() {
        val days = List(6) { DayRecord("2026-09-01", 2000) }
        val anomalies = AnomalyDetector.lowDays(days)
        assertEquals(0, anomalies.size)
    }

    @Test
    fun testNormalData() {
        val days = List(10) { DayRecord("2026-09-01", 2000) }
        val anomalies = AnomalyDetector.lowDays(days)
        assertEquals(0, anomalies.size)
    }

    @Test
    fun testOutlier() {
        val days = MutableList(10) { DayRecord("2026-09-01", 2000) }
        days.add(DayRecord("2026-09-20", 500))
        val anomalies = AnomalyDetector.lowDays(days)
        assertEquals(1, anomalies.size)
        assertEquals("2026-09-20", anomalies[0].date)
    }

    // ── Upper tail ───────────────────────────────────────────────────────────

    @Test
    fun testHighOutlier() {
        val days = MutableList(10) { DayRecord("2026-09-01", 2000) }
        days.add(DayRecord("2026-09-21", 6000))
        val anomalies = AnomalyDetector.highDays(days)
        assertEquals(1, anomalies.size)
        assertEquals("2026-09-21", anomalies[0].date)
    }

    /** A consistently heavy drinker has no outlier — only their own spikes count. */
    @Test
    fun testConsistentlyHighUserIsNotFlagged() {
        val days = List(10) { DayRecord("2026-09-01", 5000) }
        assertEquals(0, AnomalyDetector.highDays(days).size)
    }

    @Test
    fun testHighDaysNeedsEnoughHistory() {
        val days = MutableList(5) { DayRecord("2026-09-01", 2000) }
        days.add(DayRecord("2026-09-21", 6000))
        assertEquals(0, AnomalyDetector.highDays(days).size)
    }

    /** The two tails are disjoint: a low day is never reported as a high one. */
    @Test
    fun testTailsDoNotOverlap() {
        val days = MutableList(10) { DayRecord("2026-09-01", 2000) }
        days.add(DayRecord("2026-09-20", 500))
        days.add(DayRecord("2026-09-21", 6000))
        val low = AnomalyDetector.lowDays(days)
        val high = AnomalyDetector.highDays(days)
        assertEquals(0, low.intersect(high.toSet()).size)
    }
}
