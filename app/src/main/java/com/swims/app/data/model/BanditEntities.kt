package com.swims.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted Beta posterior for one (context, arm) pair of the reminder bandit.
 * Holds only aggregate pseudo-counts — never timestamps or intake amounts —
 * which is what makes this table safe to share in federated aggregation.
 */
@Entity(tableName = "bandit_arm_stats", primaryKeys = ["contextKey", "arm"])
data class BanditArmStat(
    val contextKey: String,
    val arm: Int,
    val alpha: Double = 1.0,
    val beta: Double = 1.0,
    /** Pseudo-counts already shared with the federation (for delta uploads). */
    val syncedAlpha: Double = 0.0,
    val syncedBeta: Double = 0.0,
)

/**
 * A decision the policy made, held until its reward can be attributed.
 * Resolved once the attribution window closes.
 */
@Entity(tableName = "bandit_decisions")
data class BanditDecisionRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contextKey: String,
    val arm: Int,
    val decidedAtMs: Long,
    /** ml already consumed today at decision time — used to detect a later drink. */
    val consumedAtDecisionMl: Int,
    val resolved: Boolean = false,
    val reward: Double = 0.0,
)
