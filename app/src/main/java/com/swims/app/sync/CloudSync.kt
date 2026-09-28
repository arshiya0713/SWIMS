package com.swims.app.sync

import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.swims.app.data.model.IntakeLog
import com.swims.app.data.model.UserProfile
import kotlinx.coroutines.tasks.await

/**
 * Firebase (Firestore) implementation of cloud sync.
 *
 * This is **inert until you add `app/google-services.json`** from a free Firebase
 * project (see README → "Enabling cloud sync"). Without that config
 * [isAvailable] returns false and the whole app runs local-only — which is
 * exactly the offline fallback. The Firebase SDK is on the classpath either way,
 * so this file always compiles.
 *
 * Data model (per anonymous user):
 *   users/{uid}/profile/main        ← the single profile row
 *   users/{uid}/logs/{timestampMs}  ← one doc per intake log (stable id = timestamp)
 *
 * Logs are append-only and keyed by timestamp, so pushing/pulling converges
 * across devices without a server. Profile uses last-write-wins on updatedAt.
 */
class CloudSync {

    /** True only when a real Firebase project is configured in this build. */
    fun isAvailable(): Boolean = try {
        FirebaseApp.getInstance()
        true
    } catch (e: IllegalStateException) {
        false
    } catch (e: Exception) {
        false
    }

    private val db get() = FirebaseFirestore.getInstance()
    private val auth get() = FirebaseAuth.getInstance()

    /**
     * Deletes this user's cloud copy (logs + profile) and signs out, so the
     * next launch can't pull deleted data back down. Best effort: returns
     * false if offline or unconfigured — local deletion never depends on it.
     *
     * Federated statistics are *not* touched: they're anonymous aggregate
     * sums with no per-user rows, so there is nothing attributable to delete.
     */
    suspend fun deleteRemoteDataAndSignOut(): Boolean {
        if (!isAvailable()) return false
        val uid = runCatching { auth.currentUser?.uid }.getOrNull()
        var ok = true
        if (uid != null) {
            ok = try {
                val userDoc = db.collection("users").document(uid)
                val logs = userDoc.collection("logs").get().await().documents
                logs.chunked(400).forEach { chunk ->
                    val batch = db.batch()
                    chunk.forEach { batch.delete(it.reference) }
                    batch.commit().await()
                }
                userDoc.collection("profile").document("main").delete().await()
                true
            } catch (e: Exception) {
                false
            }
        }
        runCatching { auth.signOut() }
        return ok
    }

    /** Signs in anonymously (no email/password) and returns the uid, or null. */
    suspend fun ensureSignedIn(): String? = try {
        val user = auth.currentUser ?: auth.signInAnonymously().await().user
        user?.uid
    } catch (e: Exception) {
        null
    }

    suspend fun pushProfile(uid: String, profile: UserProfile) {
        val data = mapOf(
            "weightKg" to profile.weightKg,
            "ageYears" to profile.ageYears,
            "activityLevel" to profile.activityLevel,
            "dailyGoalMl" to profile.dailyGoalMl,
            "reminderIntervalHours" to profile.reminderIntervalHours,
            "remindersEnabled" to profile.remindersEnabled,
            "smartFeaturesEnabled" to profile.smartFeaturesEnabled,
            "adaptiveGoalMl" to profile.adaptiveGoalMl,
            "updatedAt" to profile.updatedAt,
        )
        db.collection("users").document(uid)
            .collection("profile").document("main")
            .set(data, SetOptions.merge()).await()
    }

    suspend fun pullProfile(uid: String): UserProfile? = try {
        val doc = db.collection("users").document(uid)
            .collection("profile").document("main").get().await()
        if (!doc.exists()) null
        else UserProfile(
            id = 1,
            weightKg = (doc.getDouble("weightKg") ?: 70.0).toFloat(),
            ageYears = (doc.getLong("ageYears") ?: 25L).toInt(),
            activityLevel = (doc.getLong("activityLevel") ?: 1L).toInt(),
            dailyGoalMl = (doc.getLong("dailyGoalMl") ?: 2450L).toInt(),
            reminderIntervalHours = (doc.getLong("reminderIntervalHours") ?: 2L).toInt(),
            remindersEnabled = doc.getBoolean("remindersEnabled") ?: true,
            smartFeaturesEnabled = doc.getBoolean("smartFeaturesEnabled") ?: true,
            adaptiveGoalMl = (doc.getLong("adaptiveGoalMl") ?: 0L).toInt(),
            updatedAt = doc.getLong("updatedAt") ?: 0L,
        )
    } catch (e: Exception) {
        null
    }

    suspend fun pushLogs(uid: String, logs: List<IntakeLog>) {
        val col = db.collection("users").document(uid).collection("logs")
        // Chunk into batches (Firestore batch limit is 500 writes).
        logs.chunked(400).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { log ->
                val doc = col.document(log.timestampMs.toString())
                batch.set(
                    doc,
                    mapOf(
                        "amountMl" to log.amountMl,
                        "date" to log.date,
                        "timestampMs" to log.timestampMs,
                        "note" to log.note,
                        "drinkType" to log.drinkType,
                        "hydrationMl" to log.hydrationMl,
                    ),
                    SetOptions.merge(),
                )
            }
            batch.commit().await()
        }
    }

    suspend fun pullLogs(uid: String): List<IntakeLog> = try {
        val snap = db.collection("users").document(uid).collection("logs").get().await()
        snap.documents.mapNotNull { d ->
            val amount = d.getLong("amountMl")?.toInt() ?: return@mapNotNull null
            val date = d.getString("date") ?: return@mapNotNull null
            val ts = d.getLong("timestampMs") ?: return@mapNotNull null
            IntakeLog(
                id = 0, amountMl = amount, date = date, timestampMs = ts,
                note = d.getString("note"),
                drinkType = d.getString("drinkType") ?: "water",
                hydrationMl = d.getLong("hydrationMl")?.toInt() ?: amount,
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}
