package com.swims.app.ml

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * On-device hydration intelligence.
 *
 * All learning happens from the user's own intake history stored in the local
 * encrypted database — nothing is sent anywhere. The models are deliberately
 * simple, interpretable statistical learners (EWMA, hourly frequency profile,
 * z-scores, least-squares trend) so they run instantly on any device and can
 * be explained to the user.
 *
 * Pure Kotlin, no Android imports — unit-testable on the JVM.
 */

/** One day of aggregated intake. */
data class DayRecord(val date: String, val totalMl: Int)

/** A single raw log used to learn the time-of-day drinking pattern. */
data class LogPoint(val timestampMs: Long, val amountMl: Int)

/** Result of the smart reminder decision. */
data class ReminderDecision(val notify: Boolean, val message: String? = null, val reason: String)

/** Days of history required before any model starts overriding defaults. */
const val MIN_HISTORY_DAYS = 7

// ─────────────────────────────────────────────────────────────────────────────
// 1. Adaptive goal — learns what the user actually drinks and nudges the
//    formula goal toward an achievable-but-challenging personal target.
// ─────────────────────────────────────────────────────────────────────────────
object AdaptiveGoalEngine {

    private const val ALPHA = 0.25          // EWMA smoothing: recent days weigh more
    private const val STRETCH = 1.05        // aim 5% above the learned habit
    private const val MAX_DRIFT = 0.20      // never move >20% away from the formula goal

    /**
     * Returns the refined goal, or [baseGoalMl] unchanged while there is not
     * enough history (cold start).
     */
    fun refine(baseGoalMl: Int, history: List<DayRecord>): Int {
        val active = history.filter { it.totalMl > 0 }.sortedBy { it.date }
        if (active.size < MIN_HISTORY_DAYS) return baseGoalMl

        var ewma = active.first().totalMl.toDouble()
        for (day in active.drop(1)) ewma = ALPHA * day.totalMl + (1 - ALPHA) * ewma

        val blended = 0.5 * baseGoalMl + 0.5 * (ewma * STRETCH)
        val bounded = blended.coerceIn(baseGoalMl * (1 - MAX_DRIFT), baseGoalMl * (1 + MAX_DRIFT))
        val rounded = (bounded / 50.0).roundToInt() * 50
        return rounded.coerceIn(1500, 5000)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 1b. Weather adjustment — hot days need more water.
// ─────────────────────────────────────────────────────────────────────────────
object WeatherAdjuster {

    private const val COMFORT_C = 25.0   // no bonus at or below this
    private const val ML_PER_DEGREE = 40 // extra ml per °C above comfort
    private const val MAX_BONUS = 800    // cap so a heatwave can't blow the goal up

    /** Extra ml to add to the daily goal for a given day's max temperature. */
    fun bonusMl(maxTempC: Double): Int {
        if (maxTempC <= COMFORT_C) return 0
        val raw = (maxTempC - COMFORT_C) * ML_PER_DEGREE
        val rounded = (Math.round(raw / 50.0) * 50).toInt()
        return rounded.coerceIn(0, MAX_BONUS)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. Drinking-pattern model — hourly frequency profile learned from raw logs.
// ─────────────────────────────────────────────────────────────────────────────
class DrinkingPatternModel(logs: List<LogPoint>, zone: ZoneId = ZoneId.systemDefault()) {

    /** ml consumed per hour-of-day across the training window (Laplace-smoothed). */
    private val hourMl = DoubleArray(24) { 1.0 } // +1 smoothing avoids zero-division
    private val totalMl: Double

    /** Number of distinct days the model was trained on. */
    val trainingDays: Int

    init {
        val days = HashSet<LocalDate>()
        for (log in logs) {
            val t = Instant.ofEpochMilli(log.timestampMs).atZone(zone)
            hourMl[t.hour] = hourMl[t.hour] + log.amountMl.toDouble()
            days.add(t.toLocalDate())
        }
        totalMl = hourMl.sum()
        trainingDays = days.size
    }

    /** Share of a typical day's intake that happens during [hour]. */
    fun hourShare(hour: Int): Double = hourMl[hour] / totalMl

    /** Share of a typical day's intake consumed by the END of [hour]. */
    fun cumulativeShare(hour: Int): Double {
        var sum = 0.0
        for (h in 0..hour) sum += hourMl[h]
        return sum / totalMl
    }

    /** True for hours the user historically (almost) never drinks — e.g. sleep. */
    fun isQuietHour(hour: Int): Boolean = hourShare(hour) < 0.01
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. Smart reminder timing — only nudge when the user is genuinely behind.
// ─────────────────────────────────────────────────────────────────────────────
object SmartReminderEngine {

    private const val RECENT_LOG_MINUTES = 45
    private const val ON_PACE_TOLERANCE = 0.90

    fun decide(
        hourOfDay: Int,
        minutesSinceLastLog: Long?,   // null = no logs yet
        todayTotalMl: Int,
        goalMl: Int,
        model: DrinkingPatternModel,
    ): ReminderDecision {
        // Cold start: not enough history to trust the model — keep the simple
        // fixed-interval behaviour, but never wake anyone at night.
        if (model.trainingDays < MIN_HISTORY_DAYS) {
            return if (hourOfDay in 23..23 || hourOfDay in 0..5)
                ReminderDecision(false, reason = "cold-start quiet hours")
            else
                ReminderDecision(true, null, reason = "cold start — fixed interval")
        }

        if (model.isQuietHour(hourOfDay))
            return ReminderDecision(false, reason = "learned quiet hour")

        if (minutesSinceLastLog != null && minutesSinceLastLog < RECENT_LOG_MINUTES)
            return ReminderDecision(false, reason = "logged ${minutesSinceLastLog} min ago")

        val expectedByNow = model.cumulativeShare(hourOfDay) * goalMl
        if (todayTotalMl >= expectedByNow * ON_PACE_TOLERANCE)
            return ReminderDecision(false, reason = "on pace")

        val deficit = ((expectedByNow - todayTotalMl) / 50.0).roundToInt() * 50
        val message =
            if (deficit >= 200) "You're about $deficit ml behind your usual pace — time for a glass!"
            else "A quick glass now keeps you on track for today's goal."
        return ReminderDecision(true, message, reason = "behind personal pace by $deficit ml")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 4. Anomaly detection — flags unusually low-intake days via z-score.
// ─────────────────────────────────────────────────────────────────────────────
object AnomalyDetector {

    private const val Z_THRESHOLD = 1.25

    /** Returns the days whose total is significantly below the user's norm. */
    fun lowDays(history: List<DayRecord>): List<DayRecord> =
        outliers(history) { total, mean, sd ->
            total < mean - Z_THRESHOLD * sd && total < mean * 0.75
        }

    /**
     * Returns the days whose total is significantly ABOVE the user's norm.
     *
     * The mirror image of [lowDays]: the same z-score test on the upper tail.
     * A consistently high drinker is not flagged — only days that stand out
     * against that person's own baseline — so this catches a one-off binge
     * rather than a naturally high requirement.
     */
    fun highDays(history: List<DayRecord>): List<DayRecord> =
        outliers(history) { total, mean, sd ->
            total > mean + Z_THRESHOLD * sd && total > mean * 1.25
        }

    /** Shared z-score scaffolding for both tails. */
    private inline fun outliers(
        history: List<DayRecord>,
        predicate: (total: Int, mean: Double, sd: Double) -> Boolean,
    ): List<DayRecord> {
        val active = history.filter { it.totalMl > 0 }
        if (active.size < MIN_HISTORY_DAYS) return emptyList()

        val mean = active.sumOf { it.totalMl }.toDouble() / active.size
        val variance = active.sumOf { (it.totalMl - mean) * (it.totalMl - mean) } / active.size
        val sd = sqrt(variance)
        if (sd < 1.0) return emptyList() // perfectly consistent user — nothing to flag

        return active.filter { predicate(it.totalMl, mean, sd) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 4b. Over-hydration safety — the upper bound on intake.
//
// Every other model in this file is trying to get the user to drink MORE.
// This one is the counterweight. Drinking far past requirement dilutes blood
// sodium, and the kidneys can only clear roughly 0.8–1.0 litres per hour, so
// both the daily total and the short-term RATE matter.
//
// Thresholds are deliberately absolute rather than scaled to the personal
// goal: physiology does not care what target the app has learned. The only
// goal-relative rule is a suppression one — never warn a user who is still
// below their own goal, so a high-requirement athlete is not nagged.
// ─────────────────────────────────────────────────────────────────────────────
object HydrationSafety {

    /** Past this in one day, extra water stops helping. */
    const val DAILY_CAUTION_ML = 4000

    /** Well beyond any normal daily requirement. */
    const val DAILY_HIGH_ML = 6000

    /** Roughly the renal clearance ceiling — drinking faster than this backs up. */
    const val HOURLY_CAUTION_ML = 1000

    enum class Level { NORMAL, CAUTION, HIGH }

    data class Check(val level: Level, val message: String? = null) {
        val isWarning: Boolean get() = level != Level.NORMAL
    }

    private val normal = Check(Level.NORMAL)

    /**
     * Evaluates today's intake for over-hydration.
     *
     * @param todayTotalMl hydration-credited ml logged today
     * @param goalMl       the goal actually in effect (adaptive + weather)
     * @param lastHourMl   hydration-credited ml logged in the last 60 minutes
     */
    fun check(todayTotalMl: Int, goalMl: Int, lastHourMl: Int): Check {
        // Rate check first: a litre in an hour matters even on a low day total.
        if (lastHourMl >= HOURLY_CAUTION_ML) {
            return Check(
                Level.CAUTION,
                "That's $lastHourMl ml in the last hour. Your kidneys clear about " +
                    "a litre an hour — spread the next one out a bit."
            )
        }

        // Daily checks never fire while the user is still short of their goal.
        if (todayTotalMl <= goalMl) return normal

        return when {
            todayTotalMl >= DAILY_HIGH_ML -> Check(
                Level.HIGH,
                "$todayTotalMl ml today is well past what your body can use. " +
                    "Worth easing off for the rest of the day."
            )
            todayTotalMl >= DAILY_CAUTION_ML -> Check(
                Level.CAUTION,
                "You're ${todayTotalMl - goalMl} ml past your goal. " +
                    "More water won't add much from here."
            )
            else -> normal
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 5. Weekly insight generation — template NLG over the computed statistics.
// ─────────────────────────────────────────────────────────────────────────────
object InsightGenerator {

    /**
     * Builds a short, human-readable summary of the recent history.
     * [history] is the last ~28 days ascending by date; [goalMl] is the goal to
     * measure against (adaptive goal if active).
     */
    fun weekly(history: List<DayRecord>, goalMl: Int, adaptiveGoalMl: Int?): String {
        val active = history.filter { it.totalMl > 0 }.sortedBy { it.date }
        if (active.size < 3)
            return "Log a few more days and SWIMS will start spotting your personal patterns here."

        val parts = mutableListOf<String>()

        // Last-7-days performance
        val last7 = active.takeLast(7)
        val avg7 = last7.sumOf { it.totalMl } / last7.size
        val met = last7.count { it.totalMl >= goalMl }
        parts += "Over the last ${last7.size} logged days you averaged $avg7 ml and hit your goal $met time${if (met == 1) "" else "s"}."

        // Trend: least-squares slope over up to 14 days
        val window = active.takeLast(14)
        if (window.size >= 5) {
            val n = window.size
            val xMean = (n - 1) / 2.0
            val yMean = window.sumOf { it.totalMl } / n.toDouble()
            var num = 0.0; var den = 0.0
            window.forEachIndexed { i, d ->
                num += (i - xMean) * (d.totalMl - yMean)
                den += (i - xMean) * (i - xMean)
            }
            val slope = if (den == 0.0) 0.0 else num / den
            parts += when {
                slope > 25 -> "Your intake is trending up (+${slope.roundToInt()} ml/day) — keep it going!"
                slope < -25 -> "Your intake has been slipping (${slope.roundToInt()} ml/day) — worth watching."
                else -> "Your intake has been steady day to day."
            }
        }

        // Best / weakest weekday from the whole window
        if (active.size >= 10) {
            val byDow = active.groupBy { LocalDate.parse(it.date).dayOfWeek }
                .mapValues { (_, v) -> v.sumOf { it.totalMl } / v.size }
            val best = byDow.maxByOrNull { it.value }
            val worst = byDow.minByOrNull { it.value }
            if (best != null && worst != null && best.key != worst.key) {
                parts += "You drink most on ${best.key.displayName()}s and least on ${worst.key.displayName()}s."
            }
        }

        // Anomalies in the last week
        val anomalies = AnomalyDetector.lowDays(active).filter { it in last7 }
        if (anomalies.isNotEmpty()) {
            val d = LocalDate.parse(anomalies.last().date)
            parts += "⚠ ${d.dayOfWeek.displayName()} ${d.dayOfMonth}/${d.monthValue} was unusually low (${anomalies.last().totalMl} ml)."
        }

        // Adaptive goal explanation
        if (adaptiveGoalMl != null && adaptiveGoalMl > 0 && abs(adaptiveGoalMl - goalMl) < 1) {
            parts += "Your smart goal is currently $adaptiveGoalMl ml, tuned from your own history."
        }

        return parts.joinToString(" ")
    }

    private fun DayOfWeek.displayName(): String =
        getDisplayName(TextStyle.FULL, Locale.getDefault())
}
