package com.swims.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stores the user's personal profile used to calculate daily hydration goal.
 * Only one profile row is kept (id = 1).
 * No name, email, or PII is collected — only health parameters needed for the formula.
 */
@Entity(tableName = "user_profile")
data class UserProfile(
    @PrimaryKey val id: Int = 1,

    /** Body weight in kilograms — used in: goal_ml = weight * 35 */
    val weightKg: Float = 70f,

    /** Age in years — adjusts goal upward for elderly, slightly lower for young adults */
    val ageYears: Int = 25,

    /**
     * Activity level:
     *  0 = Sedentary     (+0 ml)
     *  1 = Light active   (+300 ml)
     *  2 = Moderately active (+500 ml)
     *  3 = Very active    (+750 ml)
     */
    val activityLevel: Int = 1,

    /** Calculated goal in ml — recomputed whenever profile changes */
    val dailyGoalMl: Int = 2450,

    /** Reminder interval in hours (1–4) */
    val reminderIntervalHours: Int = 2,

    /** Whether reminders are enabled */
    val remindersEnabled: Boolean = true,

    /** Whether on-device learning (smart goal, smart reminders, insights) is enabled */
    val smartFeaturesEnabled: Boolean = true,

    /**
     * Whether the adaptive reminder policy (contextual bandit) is active.
     * When false, reminders fall back to the fixed rule-based engine.
     */
    val banditEnabled: Boolean = true,

    /** Whether the device contributes anonymous policy statistics to federation. */
    val federatedEnabled: Boolean = false,

    /**
     * Goal refined by the on-device learner from the user's own history.
     * 0 = not learned yet (cold start) — the formula goal is used instead.
     */
    val adaptiveGoalMl: Int = 0,

    /** Timestamp of last profile update (epoch ms) */
    val updatedAt: Long = System.currentTimeMillis()
)
