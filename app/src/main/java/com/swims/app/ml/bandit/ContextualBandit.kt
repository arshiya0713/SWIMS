package com.swims.app.ml.bandit

import java.util.Random
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * ─────────────────────────────────────────────────────────────────────────────
 *  Contextual multi-armed bandit for Just-In-Time Adaptive Intervention (JITAI)
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Classic hydration apps fire reminders on a fixed interval. SWIMS instead
 * *learns*, per user and per context, whether a nudge is worth sending at all —
 * and which tone works. This is a Beta-Bernoulli contextual bandit solved with
 * Thompson sampling, running entirely on the device.
 *
 *  • Context  — discretised (hour-of-day bucket × hydration deficit × day type).
 *               Discretising keeps the policy interpretable and the state tiny
 *               (36 contexts), so it converges from one person's data alone.
 *  • Arms     — SKIP / GENTLE / MOTIVATIONAL (see [BanditArm]).
 *  • Reward   — did the user drink within the attribution window, minus a
 *               small cost for having interrupted them (see [Reward]).
 *  • Learning — each (context, arm) keeps a Beta(α, β) posterior over its
 *               success probability. Thompson sampling draws from each
 *               posterior and plays the argmax, which balances exploration and
 *               exploitation without any tuning parameter.
 *
 * Pure Kotlin — no Android imports — so it is unit-testable on the JVM and can
 * be driven by a simulator for offline evaluation.
 */

/** The actions the policy may take at an available decision point. */
enum class BanditArm(val id: Int, val label: String) {
    SKIP(0, "Stay silent"),
    GENTLE(1, "Gentle nudge"),
    MOTIVATIONAL(2, "Motivational nudge");

    val isNotification: Boolean get() = this != SKIP

    companion object {
        fun from(id: Int): BanditArm = entries.firstOrNull { it.id == id } ?: SKIP
    }
}

/** Discretised context. [key] is the stable identifier used for persistence. */
data class BanditContext(
    val hourBucket: Int,   // 0..5
    val deficitLevel: Int, // 0 = on pace, 1 = slightly behind, 2 = far behind
    val weekend: Boolean,
) {
    val key: String get() = "h$hourBucket-d$deficitLevel-${if (weekend) "we" else "wd"}"

    companion object {
        const val HOUR_BUCKETS = 6

        /** 6 buckets chosen to match natural daily phases, not equal clock spans. */
        fun hourBucketOf(hour: Int): Int = when (hour) {
            in 5..7 -> 0    // early morning
            in 8..10 -> 1   // morning
            in 11..13 -> 2  // midday
            in 14..16 -> 3  // afternoon
            in 17..20 -> 4  // evening
            else -> 5       // night
        }

        /**
         * How far behind the user is, relative to how much of their typical
         * daily intake they would normally have consumed by this hour.
         */
        fun deficitLevelOf(consumedMl: Int, expectedByNowMl: Double): Int {
            if (expectedByNowMl <= 0.0) return 0
            val ratio = consumedMl / expectedByNowMl
            return when {
                ratio >= 0.9 -> 0
                ratio >= 0.6 -> 1
                else -> 2
            }
        }

        fun of(hour: Int, consumedMl: Int, expectedByNowMl: Double, weekend: Boolean) =
            BanditContext(
                hourBucket = hourBucketOf(hour),
                deficitLevel = deficitLevelOf(consumedMl, expectedByNowMl),
                weekend = weekend,
            )

        /** Every context the policy can encounter — used for reporting and sync. */
        fun all(): List<BanditContext> = buildList {
            for (h in 0 until HOUR_BUCKETS)
                for (d in 0..2)
                    for (w in listOf(false, true))
                        add(BanditContext(h, d, w))
        }
    }
}

/** Beta posterior over one (context, arm) pair. */
data class ArmPosterior(
    val contextKey: String,
    val arm: BanditArm,
    val alpha: Double = 1.0, // uniform prior — no assumption before data
    val beta: Double = 1.0,
) {
    /** Posterior mean success probability. */
    val mean: Double get() = alpha / (alpha + beta)

    /** Effective observations behind this estimate. */
    val observations: Double get() = alpha + beta - 2.0

    fun updated(reward: Double): ArmPosterior {
        val r = reward.coerceIn(0.0, 1.0)
        return copy(alpha = alpha + r, beta = beta + (1.0 - r))
    }
}

/**
 * Reward shaping. Drinking is the goal; interrupting the user has a real cost,
 * so an unnecessary notification must score *worse* than staying silent.
 *
 *   silent  + drank      → 1.00  (best: hydrated without being pestered)
 *   notify  + drank      → 0.85  (worked, but cost an interruption)
 *   silent  + not drank  → 0.13  (missed an opportunity)
 *   notify  + not drank  → 0.00  (worst: annoyed them for nothing)
 *
 * Values are already in [0,1] so they can be used directly as fractional
 * Beta updates.
 */
object Reward {
    const val NOTIFICATION_COST = 0.15

    fun of(arm: BanditArm, drankWithinWindow: Boolean): Double = when {
        drankWithinWindow && !arm.isNotification -> 1.0
        drankWithinWindow && arm.isNotification -> 1.0 - NOTIFICATION_COST
        !drankWithinWindow && !arm.isNotification -> 0.13
        else -> 0.0
    }
}

/** A decision the policy made, awaiting its reward. */
data class BanditDecision(
    val contextKey: String,
    val arm: BanditArm,
    val decidedAtMs: Long,
)

/**
 * Thompson-sampling policy over the posteriors it is given.
 * Stateless: callers supply the posteriors and persist the updates, which keeps
 * this class trivially testable and lets the same logic drive a simulator.
 */
class ThompsonSamplingPolicy(private val rng: Random = Random()) {

    /** Minimum pulls of an arm in a context before we trust its posterior. */
    val warmupPulls = 2

    /**
     * Draws one sample from each arm's posterior and returns the best arm.
     * Arms that have never been tried get an optimistic first look so every
     * action is explored at least [warmupPulls] times per context.
     */
    fun selectArm(posteriors: List<ArmPosterior>): BanditArm {
        if (posteriors.isEmpty()) return BanditArm.GENTLE

        // Forced exploration: try under-sampled arms first (round-robin).
        val cold = posteriors.filter { it.observations < warmupPulls }
        if (cold.isNotEmpty()) return cold.minByOrNull { it.observations }!!.arm

        return posteriors.maxByOrNull { sampleBeta(it.alpha, it.beta) }!!.arm
    }

    /** Sample θ ~ Beta(a, b) via two Gamma draws. */
    fun sampleBeta(a: Double, b: Double): Double {
        val x = sampleGamma(a)
        val y = sampleGamma(b)
        return if (x + y <= 0.0) 0.5 else x / (x + y)
    }

    /** Marsaglia–Tsang method for Gamma(shape, 1). */
    private fun sampleGamma(shape: Double): Double {
        if (shape <= 0.0) return 0.0
        if (shape < 1.0) {
            val u = rng.nextDouble().coerceAtLeast(1e-12)
            return sampleGamma(shape + 1.0) * Math.pow(u, 1.0 / shape)
        }
        val d = shape - 1.0 / 3.0
        val c = 1.0 / sqrt(9.0 * d)
        while (true) {
            var x: Double
            var v: Double
            do {
                x = rng.nextGaussian()
                v = 1.0 + c * x
            } while (v <= 0.0)
            v = v * v * v
            val u = rng.nextDouble().coerceIn(1e-12, 1.0)
            if (u < 1.0 - 0.0331 * x * x * x * x) return d * v
            if (ln(u) < 0.5 * x * x + d * (1.0 - v + ln(v))) return d * v
        }
    }
}

/**
 * Hard safety gate applied *before* the bandit is consulted.
 *
 * In JITAI terms this is the "availability" check: the learner is never allowed
 * to explore at moments where a notification would obviously be wrong, so a
 * bad early sample can't wake the user at 3 a.m. Unavailable moments produce no
 * decision record at all, keeping the learned statistics clean.
 */
data class Availability(val available: Boolean, val reason: String) {
    companion object {
        fun available() = Availability(true, "available")
        fun no(reason: String) = Availability(false, reason)
    }
}

/** How long after a decision we wait to see whether the user drank. */
const val ATTRIBUTION_WINDOW_MS = 30 * 60 * 1000L

/** Don't intervene if the user logged this recently — they're clearly on it. */
const val RECENT_LOG_MINUTES = 45

/** One learned (context, arm) result, for the transparency screen. */
data class BanditInsight(
    val contextKey: String,
    val arm: BanditArm,
    val successRate: Double,
    val observations: Int,
) {
    /** "Evening, far behind, weekday" style description of the context key. */
    val readableContext: String
        get() {
            val parts = contextKey.split("-")
            val hour = parts.getOrNull(0)?.removePrefix("h")?.toIntOrNull() ?: 0
            val deficit = parts.getOrNull(1)?.removePrefix("d")?.toIntOrNull() ?: 0
            val weekend = parts.getOrNull(2) == "we"
            val hourName = when (hour) {
                0 -> "Early morning"; 1 -> "Morning"; 2 -> "Midday"
                3 -> "Afternoon"; 4 -> "Evening"; else -> "Night"
            }
            val deficitName = when (deficit) {
                0 -> "on pace"; 1 -> "slightly behind"; else -> "far behind"
            }
            return "$hourName · $deficitName · ${if (weekend) "weekend" else "weekday"}"
        }
}

/** Aggregate view of the learned reminder policy. */
data class BanditSummary(
    val decisionsLearned: Int,
    val notificationsSent: Int,
    val overallSuccessRate: Double,
    val topInsights: List<BanditInsight>,
)
