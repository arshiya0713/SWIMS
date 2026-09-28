package com.swims.app.ml.nlp

import com.swims.app.data.model.DrinkType

/**
 * On-device natural-language parser for drink logging.
 *
 * Turns free text — typed or dictated — into structured intake entries:
 *
 *   "a large iced coffee and 300ml of water"
 *      → [Coffee 400 ml, Water 300 ml]
 *   "two glasses of orange juice"
 *      → [Juice 500 ml]
 *   "had a cup of green tea after my run"
 *      → [Tea 240 ml, note "after my run"]
 *
 * Deliberately rule-based rather than a neural model: it runs instantly, needs
 * no model file or network, is fully deterministic, and every parse can be
 * explained. Written to be extended with more units/containers/synonyms.
 */
object DrinkTextParser {

    /** One parsed drink ready to be logged. */
    data class ParsedDrink(
        val type: DrinkType,
        val amountMl: Int,
        val note: String? = null,
        /** 0..1 — how confident the parse is, surfaced to the user for review. */
        val confidence: Double,
        val matchedText: String,
    )

    data class ParseResult(
        val drinks: List<ParsedDrink>,
        val unparsed: String?,
    ) {
        val isEmpty: Boolean get() = drinks.isEmpty()
    }

    // ── Vocabulary ───────────────────────────────────────────────────────────

    private val typeSynonyms: Map<DrinkType, List<String>> = mapOf(
        DrinkType.WATER to listOf("water", "h2o", "sparkling water", "mineral water", "aqua"),
        DrinkType.COFFEE to listOf("coffee", "espresso", "latte", "cappuccino", "americano", "mocha", "flat white", "cold brew"),
        DrinkType.TEA to listOf("tea", "chai", "green tea", "black tea", "matcha", "herbal tea", "iced tea"),
        DrinkType.JUICE to listOf("juice", "orange juice", "apple juice", "smoothie", "lemonade", "squash"),
        DrinkType.MILK to listOf("milk", "buttermilk", "lassi", "milkshake", "hot chocolate"),
    )

    /** Container words → typical volume in ml. */
    private val containers: Map<String, Int> = mapOf(
        "sip" to 50,
        "shot" to 60,
        "small cup" to 150,
        "cup" to 240,
        "mug" to 300,
        "small glass" to 200,
        "glass" to 250,
        "tumbler" to 300,
        "small bottle" to 330,
        "can" to 330,
        "bottle" to 500,
        "large bottle" to 750,
        "jug" to 1000,
    )

    /** Size adjectives scale the container/default volume. */
    private val sizeModifiers: Map<String, Double> = mapOf(
        "extra large" to 1.6, "xl" to 1.6, "venti" to 1.6,
        "large" to 1.4, "big" to 1.4, "grande" to 1.35, "tall" to 1.2,
        "regular" to 1.0, "medium" to 1.0, "standard" to 1.0,
        "small" to 0.7, "little" to 0.7, "short" to 0.7, "half" to 0.5,
    )

    private val numberWords: Map<String, Int> = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "couple" to 2, "two" to 2, "three" to 3,
        "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "nine" to 9, "ten" to 10,
    )

    /** Phrases that introduce a free-text note rather than another drink. */
    private val noteMarkers = listOf(
        "after", "before", "during", "with", "at the", "at ", "while", "post", "pre",
    )

    // ── Public API ───────────────────────────────────────────────────────────

    fun parse(rawText: String): ParseResult {
        val text = rawText.lowercase().trim()
        if (text.isEmpty()) return ParseResult(emptyList(), null)

        val note = extractNote(text)
        val drinks = mutableListOf<ParsedDrink>()

        // Split into clauses so "coffee and 300ml water" yields two entries.
        val clauses = text
            .replace(" plus ", " and ")
            .replace(",", " and ")
            .split(" and ", " then ", "&")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        for (clause in clauses) {
            parseClause(clause, note)?.let { drinks.add(it) }
        }

        // Nothing matched a drink word but there IS a quantity → assume water.
        if (drinks.isEmpty()) {
            val ml = explicitMl(text) ?: return ParseResult(emptyList(), rawText)
            if (ml in 1..3000) {
                drinks.add(
                    ParsedDrink(DrinkType.WATER, ml, note, 0.6, text)
                )
            }
        }

        return ParseResult(drinks, if (drinks.isEmpty()) rawText else null)
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private fun parseClause(clause: String, note: String?): ParsedDrink? {
        val type = detectType(clause) ?: return null
        var confidence = 0.55

        // 1. Explicit volume always wins: "300ml", "1.5 l", "2 litres"
        val explicit = explicitMl(clause)
        if (explicit != null && explicit in 1..3000) {
            return ParsedDrink(type, explicit, note, 0.95, clause)
        }

        // 2. Container + count + size modifier
        val container = containers.keys
            .filter { clause.contains(it) }
            .maxByOrNull { it.length }          // prefer "large bottle" over "bottle"
        var base = container?.let { containers[it] } ?: defaultVolume(type)
        if (container != null) confidence += 0.2

        val modifier = sizeModifiers.keys
            .filter { clause.contains(it) }
            .maxByOrNull { it.length }
        if (modifier != null) {
            base = (base * sizeModifiers.getValue(modifier)).toInt()
            confidence += 0.1
        }

        val count = detectCount(clause)
        if (count > 1) confidence += 0.05
        val total = (base * count).coerceIn(20, 3000)

        return ParsedDrink(
            type = type,
            amountMl = roundToNearest(total, 10),
            note = note,
            confidence = confidence.coerceAtMost(0.9),
            matchedText = clause,
        )
    }

    private fun detectType(clause: String): DrinkType? {
        var best: Pair<DrinkType, Int>? = null   // type, matched synonym length
        for ((type, words) in typeSynonyms) {
            for (w in words) {
                if (clause.contains(w) && (best == null || w.length > best.second)) {
                    best = type to w.length
                }
            }
        }
        return best?.first
    }

    /** Matches "500ml", "500 ml", "1.5l", "2 litres", "12 oz". */
    private fun explicitMl(text: String): Int? {
        Regex("""(\d+(?:\.\d+)?)\s*(ml|millilit(?:re|er)s?)""").find(text)?.let {
            return it.groupValues[1].toDoubleOrNull()?.toInt()
        }
        Regex("""(\d+(?:\.\d+)?)\s*(l|lit(?:re|er)s?)\b""").find(text)?.let {
            return it.groupValues[1].toDoubleOrNull()?.times(1000)?.toInt()
        }
        Regex("""(\d+(?:\.\d+)?)\s*(oz|ounces?)""").find(text)?.let {
            return it.groupValues[1].toDoubleOrNull()?.times(29.57)?.toInt()
        }
        return null
    }

    private fun detectCount(clause: String): Int {
        Regex("""(\d+)\s*(x|cups?|glass(?:es)?|bottles?|mugs?|cans?)""").find(clause)?.let {
            it.groupValues[1].toIntOrNull()?.let { n -> if (n in 1..20) return n }
        }
        for ((word, n) in numberWords) {
            if (Regex("""\b$word\b""").containsMatchIn(clause)) return n
        }
        return 1
    }

    private fun extractNote(text: String): String? {
        for (marker in noteMarkers) {
            val idx = text.indexOf(" $marker")
            if (idx > 0) {
                val note = text.substring(idx).trim()
                // Avoid capturing "with milk" style modifiers as a note.
                if (note.length in 4..60 && detectType(note) == null) {
                    return note.replaceFirstChar { it.uppercase() }
                }
            }
        }
        return null
    }

    private fun defaultVolume(type: DrinkType): Int = when (type) {
        DrinkType.WATER -> 250
        DrinkType.COFFEE -> 240
        DrinkType.TEA -> 240
        DrinkType.JUICE -> 250
        DrinkType.MILK -> 250
    }

    private fun roundToNearest(value: Int, step: Int): Int =
        ((value + step / 2) / step) * step
}
