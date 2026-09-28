package com.swims.app.data.model

/**
 * Drink types with hydration factors — not everything hydrates like water.
 * A 200 ml coffee credits 180 ml (90%) toward the daily goal.
 * Factors follow common hydration-tracker conventions.
 */
enum class DrinkType(val key: String, val emoji: String, val label: String, val factor: Double) {
    WATER("water", "💧", "Water", 1.00),
    TEA("tea", "🍵", "Tea", 0.95),
    COFFEE("coffee", "☕", "Coffee", 0.90),
    JUICE("juice", "🧃", "Juice", 0.85),
    MILK("milk", "🥛", "Milk", 0.90);

    /** ml credited toward the goal for [amountMl] of this drink. */
    fun hydration(amountMl: Int): Int = Math.round(amountMl * factor).toInt()

    companion object {
        fun from(key: String?): DrinkType = entries.firstOrNull { it.key == key } ?: WATER
    }
}
