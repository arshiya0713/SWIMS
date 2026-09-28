package com.swims.app.util

import java.time.LocalDate
import java.time.format.DateTimeFormatter

object GoalCalculator {
    fun calculate(weightKg: Float, ageYears: Int, activityLevel: Int): Int {
        val base = (weightKg * 35).toInt()
        val ageFactor = when {
            ageYears < 18 -> -100
            ageYears > 55 -> +200
            else -> 0
        }
        val activityBonus = when (activityLevel) {
            0 -> 0
            1 -> 300
            2 -> 500
            3 -> 750
            else -> 300
        }
        return (base + ageFactor + activityBonus).coerceIn(1500, 5000)
    }
}

object DateUtil {
    private val fmt = DateTimeFormatter.ISO_LOCAL_DATE
    fun today(): String = LocalDate.now().format(fmt)
    fun daysAgo(n: Long): String = LocalDate.now().minusDays(n).format(fmt)
}
