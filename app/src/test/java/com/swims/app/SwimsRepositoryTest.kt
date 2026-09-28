package com.swims.app.data.repository

import com.swims.app.data.db.SwimsDao
import com.swims.app.data.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import androidx.lifecycle.LiveData
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class SwimsRepositoryTest {

    class FakeDao : SwimsDao {
        var daysGoalMet = mutableListOf<DailyStats>()

        override suspend fun getDaysGoalMet(fromDate: String, goalMl: Int): List<DailyStats> = daysGoalMet

        override suspend fun upsertProfile(profile: UserProfile) {}
        override fun observeProfile(): LiveData<UserProfile?> = throw NotImplementedError()
        override suspend fun getProfile(): UserProfile? = null
        override suspend fun deleteProfile() {}
        override suspend fun insertLog(log: IntakeLog): Long = 0
        override suspend fun deleteLog(log: IntakeLog) {}
        override suspend fun deleteAllLogs() {}
        override fun observeLogsForDate(date: String): LiveData<List<IntakeLog>> = throw NotImplementedError()
        override fun observeTotalForDate(date: String): LiveData<Int> = throw NotImplementedError()
        override fun observeDailyStats(fromDate: String): LiveData<List<DailyStats>> = throw NotImplementedError()
        override suspend fun getLogsSince(fromDate: String): List<IntakeLog> = emptyList()
        override suspend fun getDailyStats(fromDate: String): List<DailyStats> = emptyList()
        override suspend fun getTotalForDate(date: String): Int = 0
        override suspend fun getLastLogTime(): Long? = null
        override suspend fun getAllLogs(): List<IntakeLog> = emptyList()
        override suspend fun getAllLogTimestamps(): List<Long> = emptyList()
        override suspend fun getTypeStats(): List<TypeStat> = emptyList()
        override suspend fun getArmStats(contextKey: String): List<BanditArmStat> = emptyList()
        override suspend fun getAllArmStats(): List<BanditArmStat> = emptyList()
        override suspend fun upsertArmStat(stat: BanditArmStat) {}
        override suspend fun upsertArmStats(stats: List<BanditArmStat>) {}
        override suspend fun deleteAllArmStats() {}
        override suspend fun insertDecision(decision: BanditDecisionRecord): Long = 0
        override suspend fun getUnresolvedDecisions(cutoffMs: Long): List<BanditDecisionRecord> = emptyList()
        override suspend fun resolveDecision(id: Long, reward: Double) {}
        override suspend fun getRecentDecisions(limit: Int): List<BanditDecisionRecord> = emptyList()
        override suspend fun getResolvedDecisionCount(): Int = 0
        override suspend fun deleteAllDecisions() {}
        override suspend fun getHydrationBetween(sinceMs: Long, untilMs: Long): Int = 0
    }

    @Test
    fun testStreak() = runBlocking {
        val dao = FakeDao()
        val repo = SwimsRepository(dao)
        val fmt = DateTimeFormatter.ISO_LOCAL_DATE
        val today = LocalDate.now()

        // 1. Empty history -> 0
        assertEquals(0, repo.calculateStreak(2000))

        // 2. First day (today) -> 1
        dao.daysGoalMet = mutableListOf(DailyStats(today.format(fmt), 2000, 1))
        assertEquals(1, repo.calculateStreak(2000))

        // 3. Yesterday but not today -> 1
        val yesterday = today.minusDays(1)
        dao.daysGoalMet = mutableListOf(DailyStats(yesterday.format(fmt), 2000, 1))
        assertEquals(1, repo.calculateStreak(2000))

        // 4. Consecutive days
        val twoDaysAgo = today.minusDays(2)
        dao.daysGoalMet = mutableListOf(
            DailyStats(today.format(fmt), 2000, 1),
            DailyStats(yesterday.format(fmt), 2000, 1),
            DailyStats(twoDaysAgo.format(fmt), 2000, 1)
        )
        assertEquals(3, repo.calculateStreak(2000))

        // 5. Missed day
        val threeDaysAgo = today.minusDays(3)
        // missed yesterday!
        dao.daysGoalMet = mutableListOf(
            DailyStats(today.format(fmt), 2000, 1),
            DailyStats(twoDaysAgo.format(fmt), 2000, 1),
            DailyStats(threeDaysAgo.format(fmt), 2000, 1)
        )
        assertEquals(1, repo.calculateStreak(2000)) // Streak is broken yesterday, so it's only today -> 1. 
    }
}
