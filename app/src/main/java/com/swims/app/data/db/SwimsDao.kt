package com.swims.app.data.db

import androidx.lifecycle.LiveData
import androidx.room.*
import com.swims.app.data.model.BanditArmStat
import com.swims.app.data.model.BanditDecisionRecord
import com.swims.app.data.model.DailyStats
import com.swims.app.data.model.IntakeLog
import com.swims.app.data.model.TypeStat
import com.swims.app.data.model.UserProfile

@Dao
interface SwimsDao {

    // ──────────────────────────────────────────────
    // USER PROFILE
    // ──────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: UserProfile)

    @Query("SELECT * FROM user_profile WHERE id = 1 LIMIT 1")
    fun observeProfile(): LiveData<UserProfile?>

    @Query("SELECT * FROM user_profile WHERE id = 1 LIMIT 1")
    suspend fun getProfile(): UserProfile?

    @Query("DELETE FROM user_profile")
    suspend fun deleteProfile()

    // ──────────────────────────────────────────────
    // INTAKE LOGS
    // ──────────────────────────────────────────────

    @Insert
    suspend fun insertLog(log: IntakeLog): Long

    @Delete
    suspend fun deleteLog(log: IntakeLog)

    @Query("DELETE FROM intake_log")
    suspend fun deleteAllLogs()

    /** All logs for a specific date, newest first */
    @Query("SELECT * FROM intake_log WHERE date = :date ORDER BY timestampMs DESC")
    fun observeLogsForDate(date: String): LiveData<List<IntakeLog>>

    /** Total ml consumed on a specific date */
    @Query("SELECT COALESCE(SUM(hydrationMl), 0) FROM intake_log WHERE date = :date")
    fun observeTotalForDate(date: String): LiveData<Int>

    /** Daily totals for the last N days — used for weekly/monthly charts */
    @Query("""
        SELECT date, SUM(hydrationMl) AS totalMl, COUNT(*) AS logCount
        FROM intake_log
        WHERE date >= :fromDate
        GROUP BY date
        ORDER BY date ASC
    """)
    fun observeDailyStats(fromDate: String): LiveData<List<DailyStats>>

    // ── Suspend variants used by the on-device ML engines ──

    /** Raw logs since a date — trains the time-of-day drinking pattern model */
    @Query("SELECT * FROM intake_log WHERE date >= :fromDate")
    suspend fun getLogsSince(fromDate: String): List<IntakeLog>

    /** Daily totals since a date — feeds adaptive goal, anomalies and insights */
    @Query("""
        SELECT date, SUM(hydrationMl) AS totalMl, COUNT(*) AS logCount
        FROM intake_log
        WHERE date >= :fromDate
        GROUP BY date
        ORDER BY date ASC
    """)
    suspend fun getDailyStats(fromDate: String): List<DailyStats>

    /** Total ml for one date (non-observing) */
    @Query("SELECT COALESCE(SUM(hydrationMl), 0) FROM intake_log WHERE date = :date")
    suspend fun getTotalForDate(date: String): Int

    /** Timestamp of the most recent log, or null if none */
    @Query("SELECT MAX(timestampMs) FROM intake_log")
    suspend fun getLastLogTime(): Long?

    /** All logs — used by cloud sync to push the full history */
    @Query("SELECT * FROM intake_log")
    suspend fun getAllLogs(): List<IntakeLog>

    /** Timestamps already stored locally — used to dedupe pulled remote logs */
    @Query("SELECT timestampMs FROM intake_log")
    suspend fun getAllLogTimestamps(): List<Long>

    /** Per-drink-type usage — powers the Ask-SWIMS Q&A. */
    @Query("""
        SELECT drinkType, COUNT(*) AS count, SUM(amountMl) AS totalMl,
               SUM(hydrationMl) AS totalHydrationMl
        FROM intake_log GROUP BY drinkType ORDER BY count DESC
    """)
    suspend fun getTypeStats(): List<TypeStat>

    // ──────────────────────────────────────────────
    // CONTEXTUAL BANDIT (JITAI reminder policy)
    // ──────────────────────────────────────────────

    @Query("SELECT * FROM bandit_arm_stats WHERE contextKey = :contextKey")
    suspend fun getArmStats(contextKey: String): List<BanditArmStat>

    @Query("SELECT * FROM bandit_arm_stats")
    suspend fun getAllArmStats(): List<BanditArmStat>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArmStat(stat: BanditArmStat)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArmStats(stats: List<BanditArmStat>)

    @Query("DELETE FROM bandit_arm_stats")
    suspend fun deleteAllArmStats()

    @Insert
    suspend fun insertDecision(decision: BanditDecisionRecord): Long

    /** Decisions whose attribution window has closed but that are still unrewarded. */
    @Query("SELECT * FROM bandit_decisions WHERE resolved = 0 AND decidedAtMs <= :cutoffMs")
    suspend fun getUnresolvedDecisions(cutoffMs: Long): List<BanditDecisionRecord>

    @Query("UPDATE bandit_decisions SET resolved = 1, reward = :reward WHERE id = :id")
    suspend fun resolveDecision(id: Long, reward: Double)

    /** Most recent decisions, newest first — powers the transparency screen. */
    @Query("SELECT * FROM bandit_decisions ORDER BY decidedAtMs DESC LIMIT :limit")
    suspend fun getRecentDecisions(limit: Int): List<BanditDecisionRecord>

    @Query("SELECT COUNT(*) FROM bandit_decisions WHERE resolved = 1")
    suspend fun getResolvedDecisionCount(): Int

    @Query("DELETE FROM bandit_decisions")
    suspend fun deleteAllDecisions()

    /** Total ml logged strictly after a timestamp — used for reward attribution. */
    @Query("SELECT COALESCE(SUM(hydrationMl), 0) FROM intake_log WHERE timestampMs > :sinceMs AND timestampMs <= :untilMs")
    suspend fun getHydrationBetween(sinceMs: Long, untilMs: Long): Int

    /** Dates within range where goal was met — for streak calculation */
    @Query("""
        SELECT date, SUM(hydrationMl) AS totalMl, COUNT(*) AS logCount
        FROM intake_log
        WHERE date >= :fromDate
        GROUP BY date
        HAVING SUM(hydrationMl) >= :goalMl
        ORDER BY date DESC
    """)
    suspend fun getDaysGoalMet(fromDate: String, goalMl: Int): List<DailyStats>
}
