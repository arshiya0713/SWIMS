package com.swims.app.sync

import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.swims.app.data.model.BanditArmStat
import kotlinx.coroutines.tasks.await
import java.util.Random
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sign

/**
 * ─────────────────────────────────────────────────────────────────────────────
 *  Federated learning for the reminder policy (federated Thompson sampling)
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * A single user generates few reminder decisions per day, so a purely local
 * bandit takes weeks to converge. Federation fixes the cold-start problem
 * *without* centralising anyone's data.
 *
 * What is uploaded: only the change in Beta pseudo-counts (α, β) per
 * (context, arm) since the last round — aggregate success/failure weights.
 * What is never uploaded: intake amounts, timestamps, drink types, weight,
 * age, location, or any identifier beyond an anonymous auth uid.
 *
 * Privacy hardening applied before anything leaves the device:
 *   1. Clipping     — each contribution is capped at [CLIP], bounding the
 *                     influence (and the sensitivity) of any single user.
 *   2. DP noise     — Laplace noise at scale CLIP/ε implements ε-differential
 *                     privacy over one user's round contribution.
 *   3. Aggregation  — Firestore atomic increments mean the server only ever
 *                     stores the running sum, never per-user rows.
 *
 * Downloaded global posteriors are folded in as a *weak prior*
 * ([PRIOR_STRENGTH] pseudo-counts), so the population shapes the policy on
 * day one but the user's own behaviour dominates as their data accumulates.
 *
 * Inert unless a Firebase project is configured AND the user opts in.
 */
class FederatedPolicyClient {

    /** ε for the Laplace mechanism. Lower = more private, noisier. */
    private val epsilon = 1.0

    /** Max pseudo-counts one device may contribute per (context, arm) per round. */
    private val CLIP = 10.0

    /** Strength of the global prior when blended into a local posterior. */
    private val PRIOR_STRENGTH = 4.0

    private val rng = Random()

    fun isAvailable(): Boolean = try {
        FirebaseApp.getInstance(); true
    } catch (e: Exception) {
        false
    }

    private val db get() = FirebaseFirestore.getInstance()
    private val auth get() = FirebaseAuth.getInstance()

    private suspend fun ensureSignedIn(): String? = try {
        (auth.currentUser ?: auth.signInAnonymously().await().user)?.uid
    } catch (e: Exception) {
        null
    }

    data class RoundResult(
        val uploadedContexts: Int,
        val downloadedContexts: Int,
        val contributors: Int,
    )

    /** Why a round could not run, so the UI can say something useful. */
    sealed interface Outcome {
        data class Completed(val result: RoundResult) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /**
     * Runs one federated round: upload the local delta, then pull the global
     * aggregate back and blend it into the local posteriors as a prior.
     *
     * @param local     current local posteriors
     * @param onUpdated receives posteriors with the global prior folded in and
     *                  the synced watermark advanced — caller persists them
     */
    suspend fun runRound(
        local: List<BanditArmStat>,
        onUpdated: suspend (List<BanditArmStat>) -> Unit,
    ): Outcome {
        if (!isAvailable()) return Outcome.Failed("Firebase isn't configured in this build.")
        ensureSignedIn()
            ?: return Outcome.Failed("Couldn't reach Firebase Auth — check the connection and that Anonymous sign-in is enabled.")

        // ── 1. Upload privatised deltas ──
        // A device with no local evidence yet simply uploads nothing and still
        // downloads the population model — that's the cold-start benefit.
        var uploaded = 0
        for (stat in local) {
            val dAlpha = stat.alpha - stat.syncedAlpha
            val dBeta = stat.beta - stat.syncedBeta
            if (dAlpha <= 0.0 && dBeta <= 0.0) continue

            val a = privatise(dAlpha)
            val b = privatise(dBeta)
            if (a <= 0.0 && b <= 0.0) continue

            try {
                db.collection("federated_policy")
                    .document("${stat.contextKey}__${stat.arm}")
                    .set(
                        mapOf(
                            "contextKey" to stat.contextKey,
                            "arm" to stat.arm,
                            "alphaSum" to FieldValue.increment(a),
                            "betaSum" to FieldValue.increment(b),
                            "contributions" to FieldValue.increment(1L),
                        ),
                        SetOptions.merge(),
                    ).await()
                uploaded++
            } catch (e: Exception) {
                // Network hiccup — keep the watermark so we retry next round.
                return Outcome.Failed(
                    "Upload failed: ${e.message?.take(90) ?: "network error"}. " +
                        "Check Firestore is created and its rules allow writes."
                )
            }
        }

        // ── 2. Download the global aggregate ──
        val global = try {
            db.collection("federated_policy").get().await().documents.mapNotNull { d ->
                val key = d.getString("contextKey") ?: return@mapNotNull null
                val arm = d.getLong("arm")?.toInt() ?: return@mapNotNull null
                val aSum = d.getDouble("alphaSum") ?: 0.0
                val bSum = d.getDouble("betaSum") ?: 0.0
                val n = (d.getLong("contributions") ?: 1L).coerceAtLeast(1L)
                Triple(key to arm, (aSum / n) to (bSum / n), n.toInt())
            }
        } catch (e: Exception) {
            return Outcome.Failed(
                "Download failed: ${e.message?.take(90) ?: "network error"}. " +
                    "Check Firestore exists and its rules allow reads."
            )
        }
        if (global.isEmpty()) {
            return if (uploaded > 0) {
                Outcome.Completed(RoundResult(uploaded, 0, 0))
            } else {
                Outcome.Failed(
                    "Nothing to share yet and no global model exists — this device is the first " +
                        "contributor. The policy needs a few resolved reminder decisions first."
                )
            }
        }

        val globalMap = global.associate { it.first to it.second }
        val contributors = global.maxOfOrNull { it.third } ?: 0
        val localByKey = local.associateBy { it.contextKey to it.arm }

        // ── 3. Blend the global mean in as a weak prior ──
        // Iterate the union of local and global keys, so a device that has no
        // local statistics yet still seeds its posteriors from the population.
        val merged = (localByKey.keys + globalMap.keys).map { key ->
            val stat = localByKey[key]
                ?: BanditArmStat(contextKey = key.first, arm = key.second)
            val g = globalMap[key]
            if (g == null) {
                stat.copy(syncedAlpha = stat.alpha, syncedBeta = stat.beta)
            } else {
                val (gA, gB) = g
                val total = (gA + gB).coerceAtLeast(1e-6)
                val priorA = PRIOR_STRENGTH * (gA / total)
                val priorB = PRIOR_STRENGTH * (gB / total)
                // Rebase: uniform prior (1,1) replaced by the population prior,
                // leaving this user's own observed evidence untouched.
                stat.copy(
                    alpha = (stat.alpha - 1.0).coerceAtLeast(0.0) + 1.0 + priorA,
                    beta = (stat.beta - 1.0).coerceAtLeast(0.0) + 1.0 + priorB,
                    syncedAlpha = stat.alpha,
                    syncedBeta = stat.beta,
                )
            }
        }
        onUpdated(merged)

        return Outcome.Completed(RoundResult(uploaded, globalMap.size, contributors))
    }

    /** Clip, then add Laplace(CLIP/ε) noise. Negative results are floored at 0. */
    private fun privatise(value: Double): Double {
        val clipped = value.coerceIn(0.0, CLIP)
        if (clipped <= 0.0) return 0.0
        val noised = clipped + laplace(CLIP / epsilon)
        return noised.coerceAtLeast(0.0)
    }

    /** Inverse-CDF sampling from Laplace(0, scale). */
    private fun laplace(scale: Double): Double {
        val u = rng.nextDouble() - 0.5
        return -scale * sign(u) * ln(1.0 - 2.0 * abs(u).coerceAtMost(0.499999))
    }
}
