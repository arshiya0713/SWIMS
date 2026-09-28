package com.swims.app.data.model

/**
 * Result of a GROUP BY date query — not an entity, just a projection.
 */
data class DailyStats(
    val date: String,
    val totalMl: Int,
    val logCount: Int
)

/** Result of a GROUP BY drinkType query — projection for the Q&A engine. */
data class TypeStat(
    val drinkType: String,
    val count: Int,
    val totalMl: Int,
    val totalHydrationMl: Int,
)
