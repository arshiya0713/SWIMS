package com.swims.app.ml

import com.swims.app.data.model.IntakeLog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One badge: identity, art, and whether the user has earned it. */
data class Achievement(
    val id: String,
    val emoji: String,
    val title: String,
    val description: String,
    val unlocked: Boolean,
)

/**
 * Badge engine — computed on demand from real history, entirely on-device.
 * Deterministic and stateless: re-running on the same data gives the same
 * result, so nothing needs to be stored except (optionally) which unlocks
 * were already announced.
 */
object AchievementEngine {

    /**
     * @param logs            full log history
     * @param goalMetDates    dates ("YYYY-MM-DD") where the daily goal was met
     */
    fun compute(logs: List<IntakeLog>, goalMetDates: Set<String>): List<Achievement> {
        val zone = ZoneId.systemDefault()
        val lifetimeMl = logs.sumOf { it.hydrationMl.toLong() }
        val distinctLogDays = logs.map { it.date }.toSortedSet()
        val earlyDays = logs.filter {
            Instant.ofEpochMilli(it.timestampMs).atZone(zone).hour < 9
        }.map { it.date }.toSet()

        val bestGoalRun = longestConsecutiveRun(goalMetDates)
        val bestLogRun = longestConsecutiveRun(distinctLogDays)

        return listOf(
            Achievement("first_drop", "💧", "First Drop", "Log your very first drink", logs.isNotEmpty()),
            Achievement("week_fire", "🔥", "On Fire", "Meet your goal 7 days in a row", bestGoalRun >= 7),
            Achievement("month_master", "🏆", "Month Master", "Meet your goal 30 days in a row", bestGoalRun >= 30),
            Achievement("habit_builder", "📅", "Habit Builder", "Log something 14 days in a row", bestLogRun >= 14),
            Achievement("ten_litres", "🌊", "10 L Club", "Drink 10 litres lifetime", lifetimeMl >= 10_000),
            Achievement("hundred_litres", "🐋", "100 L Club", "Drink 100 litres lifetime", lifetimeMl >= 100_000),
            Achievement("early_bird", "🌅", "Early Bird", "Log before 9 am on 10 different days", earlyDays.size >= 10),
            Achievement("century", "💯", "Century", "Log 100 drinks in total", logs.size >= 100),
        )
    }

    /** Longest run of consecutive calendar dates in the set. */
    private fun longestConsecutiveRun(dates: Collection<String>): Int {
        if (dates.isEmpty()) return 0
        val sorted = dates.toSortedSet().map { LocalDate.parse(it) }
        var best = 1
        var run = 1
        for (i in 1 until sorted.size) {
            run = if (sorted[i] == sorted[i - 1].plusDays(1)) run + 1 else 1
            if (run > best) best = run
        }
        return best
    }
}
