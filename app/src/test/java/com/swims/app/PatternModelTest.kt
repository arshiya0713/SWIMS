package com.swims.app.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class PatternModelTest {
    @Test
    fun testEmptyData() {
        val model = DrinkingPatternModel(emptyList())
        assertEquals(1.0 / 24, model.hourShare(5), 0.0001)
        assertEquals(0, model.trainingDays)
    }

    @Test
    fun testHourlyDistribution() {
        val zone = ZoneId.of("UTC")
        val logs = listOf(
            LogPoint(1725177600000L, 1000), // Sep 1, 2024 8:00 AM UTC
            LogPoint(1725199200000L, 1000)  // Sep 1, 2024 2:00 PM UTC (14)
        )
        val model = DrinkingPatternModel(logs, zone)
        
        // total smoothed: 2000 (actual) + 24 (laplace) = 2024
        val expected8am = 1001.0 / 2024.0
        val expected14 = 1001.0 / 2024.0
        val expectedOther = 1.0 / 2024.0
        
        assertEquals(expected8am, model.hourShare(8), 0.0001)
        assertEquals(expected14, model.hourShare(14), 0.0001)
        assertEquals(expectedOther, model.hourShare(15), 0.0001)
        
        assertEquals(expectedOther * 8, model.cumulativeShare(7), 0.0001)
        assertEquals(expectedOther * 8 + expected8am, model.cumulativeShare(8), 0.0001)
        
        // quiet hour check
        assertTrue(model.isQuietHour(15)) // 1/2024 < 0.01
    }
}
