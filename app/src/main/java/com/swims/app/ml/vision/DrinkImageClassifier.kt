package com.swims.app.ml.vision

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.swims.app.data.model.DrinkType
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Photo-based drink recognition using ML Kit's bundled on-device image labeler.
 *
 * The TFLite model ships inside the APK, so classification runs offline, costs
 * nothing, needs no API key, and — importantly for this app — the photo never
 * leaves the device.
 *
 * The labeler returns generic visual concepts ("Coffee", "Bottle", "Juice"),
 * which we map onto a [DrinkType] plus a volume estimated from whichever
 * container was recognised.
 */
class DrinkImageClassifier {

    data class Recognition(
        val type: DrinkType,
        val estimatedMl: Int,
        val confidence: Double,
        /** Raw labels, shown to the user so the guess is never a black box. */
        val labels: List<String>,
        val containerLabel: String?,
    )

    /**
     * Outcome of a classification. On failure we still hand back what the model
     * *did* see, so the UI can explain itself instead of just saying "no".
     */
    sealed interface Outcome {
        data class Found(val recognition: Recognition) : Outcome
        data class NotFound(val labels: List<String>) : Outcome
    }

    private val labeler by lazy {
        ImageLabeling.getClient(
            ImageLabelerOptions.Builder()
                // The base model spreads probability over ~400 generic classes,
                // so useful drink labels routinely land in the 0.3–0.6 band.
                .setConfidenceThreshold(0.3f)
                .build()
        )
    }

    /** Labels that identify what is being drunk. */
    private val typeHints: Map<DrinkType, List<String>> = mapOf(
        DrinkType.COFFEE to listOf("coffee", "espresso", "latte", "cappuccino", "caffeine"),
        DrinkType.TEA to listOf("tea", "matcha", "herbal"),
        DrinkType.JUICE to listOf("juice", "smoothie", "lemonade", "orange", "cocktail", "soft drink", "soda"),
        DrinkType.MILK to listOf("milk", "milkshake", "dairy", "yogurt", "hot chocolate"),
        DrinkType.WATER to listOf(
            "water", "mineral water", "bottled water", "drinking water",
            "liquid", "bubble", "fluid", "aqua", "ice",
        ),
    )

    /**
     * Generic "this is a drink" evidence. The base model often recognises the
     * beverage context without naming the liquid — that's still enough to log,
     * defaulting to water (by far the most common case).
     */
    private val genericDrinkHints = listOf(
        "drink", "beverage", "drinkware", "tableware", "serveware", "refreshment",
    )

    /** Labels that identify the vessel → typical volume. */
    private val containerHints: List<Pair<String, Int>> = listOf(
        "water bottle" to 500,
        "bottle" to 500,
        "jug" to 1000,
        "pitcher" to 1000,
        "flask" to 750,
        "thermos" to 500,
        "mug" to 300,
        "cup" to 240,
        "teacup" to 200,
        "glass" to 250,
        "tumbler" to 300,
        "can" to 330,
        "wine glass" to 200,
        "straw" to 350,
    )

    /**
     * Classifies [bitmap], returning null when nothing drink-like is recognised.
     * Suspends until the on-device model finishes (typically < 100 ms).
     */
    suspend fun classify(bitmap: Bitmap): Outcome =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            labeler.process(image)
                .addOnSuccessListener { labels ->
                    val texts = labels.map { it.text.lowercase() }
                    val confidences = labels.associate { it.text.lowercase() to it.confidence.toDouble() }

                    // Which drink is it?
                    var best: Triple<DrinkType, String, Double>? = null
                    for ((type, hints) in typeHints) {
                        for (hint in hints) {
                            val match = texts.firstOrNull { it.contains(hint) } ?: continue
                            val c = confidences[match] ?: 0.0
                            if (best == null || c > best.third) best = Triple(type, match, c)
                        }
                    }

                    // Which container is it in?
                    val container = containerHints.firstOrNull { (hint, _) ->
                        texts.any { it.contains(hint) }
                    }

                    // Generic beverage evidence, when the liquid isn't named.
                    val generic = genericDrinkHints.firstOrNull { hint ->
                        texts.any { it.contains(hint) }
                    }

                    if (best == null && container == null && generic == null) {
                        cont.resume(Outcome.NotFound(labels.map { it.text }))
                        return@addOnSuccessListener
                    }

                    // A recognised vessel (or generic beverage cue) with no
                    // identifiable contents is most likely water.
                    val type = best?.first ?: DrinkType.WATER
                    val confidence = best?.third
                        ?: confidences[generic] ?: confidences[container?.first] ?: 0.45

                    cont.resume(
                        Outcome.Found(
                            Recognition(
                                type = type,
                                estimatedMl = container?.second ?: 250,
                                confidence = confidence,
                                labels = labels.map { it.text },
                                containerLabel = container?.first,
                            )
                        )
                    )
                }
                .addOnFailureListener { cont.resume(Outcome.NotFound(emptyList())) }
        }

    fun close() = labeler.close()
}
