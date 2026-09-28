package com.swims.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Each water intake entry logged by the user.
 * No user identifier beyond the single-user device app — no PII stored here.
 */
@Entity(tableName = "intake_log")
data class IntakeLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Amount consumed in millilitres */
    val amountMl: Int,

    /**
     * Date stored as "YYYY-MM-DD" string for easy GROUP BY queries.
     * Storing as text avoids timezone conversion bugs with Long epoch.
     */
    val date: String,   // e.g. "2024-03-15"

    /** Epoch milliseconds — used for display (HH:mm) and ordering within a day */
    val timestampMs: Long = System.currentTimeMillis(),

    /** Optional note (e.g. "after gym") — nullable, user-provided */
    val note: String? = null,

    /** Drink type key — see [DrinkType]. Defaults to water. */
    val drinkType: String = "water",

    /**
     * ml credited toward the goal after the drink's hydration factor
     * (coffee counts 90%, juice 85%, …). All totals/stats sum THIS field.
     */
    val hydrationMl: Int = 0
)
