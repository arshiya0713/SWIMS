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
}
