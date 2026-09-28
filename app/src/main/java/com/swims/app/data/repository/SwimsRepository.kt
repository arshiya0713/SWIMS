package com.swims.app.data.repository

import android.content.Context
import androidx.lifecycle.LiveData
import com.swims.app.data.SettingsStore
import com.swims.app.data.db.SwimsDao
import com.swims.app.data.model.DailyStats
import com.swims.app.data.model.DrinkType
import com.swims.app.data.model.IntakeLog
import com.swims.app.data.model.UserProfile
import com.swims.app.data.model.BanditArmStat
import com.swims.app.data.model.BanditDecisionRecord
import com.swims.app.ml.AdaptiveGoalEngine
import com.swims.app.ml.DayRecord
import com.swims.app.ml.DrinkingPatternModel
import com.swims.app.ml.InsightGenerator
import com.swims.app.ml.LogPoint
import com.swims.app.ml.MIN_HISTORY_DAYS
import com.swims.app.ml.ReminderDecision
import com.swims.app.ml.SmartReminderEngine
import com.swims.app.ml.bandit.ATTRIBUTION_WINDOW_MS
import com.swims.app.ml.bandit.ArmPosterior
import com.swims.app.ml.bandit.Availability
import com.swims.app.ml.bandit.BanditArm
import com.swims.app.ml.bandit.BanditContext
import com.swims.app.ml.bandit.BanditInsight
import com.swims.app.ml.bandit.BanditSummary
import com.swims.app.ml.bandit.RECENT_LOG_MINUTES
import com.swims.app.ml.bandit.Reward
import com.swims.app.ml.bandit.ThompsonSamplingPolicy
import kotlin.math.roundToInt
import com.swims.app.network.NetworkUtil
import com.swims.app.sync.CloudSync
import com.swims.app.sync.FederatedPolicyClient
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class SwimsRepository(private val dao: SwimsDao) {

    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    // ── Profile ──────────────────────────────────────────────────────────────

    fun observeProfile(): LiveData<UserProfile?> = dao.observeProfile()

    suspend fun saveProfile(profile: UserProfile) = dao.upsertProfile(profile)

    suspend fun getProfile(): UserProfile? = dao.getProfile()

    /**
     * Calculates daily water goal in ml using standard formula:
     *   base = weight(kg) × 35 ml
     *   age adjustment: +200ml if >55, -100ml if <18
     *   activity bonus: 0 / 300 / 500 / 750 ml
     */
    fun calculateGoal(weightKg: Float, ageYears: Int, activityLevel: Int): Int {
        val base = (weightKg * 35).toInt()
        val ageFactor = when {
            ageYears < 18 -> -100
            ageYears > 55 -> +200
            else -> 0
        }
        val activityBonus = when (activityLevel) {
            0 -> 0
            1 -> 300
            2 -> 500
            3 -> 750
            else -> 300
        }
        return (base + ageFactor + activityBonus).coerceIn(1500, 5000)
    }

    // ── Intake logs ──────────────────────────────────────────────────────────

    fun observeLogsForToday(): LiveData<List<IntakeLog>> =
        dao.observeLogsForDate(today())

    fun observeTotalForToday(): LiveData<Int> =
        dao.observeTotalForDate(today())

    fun observeDailyStats(days: Int): LiveData<List<DailyStats>> {
        val fromDate = LocalDate.now().minusDays(days.toLong()).format(dateFormatter)
        return dao.observeDailyStats(fromDate)
    }

    suspend fun logIntake(amountMl: Int, note: String? = null, type: DrinkType = DrinkType.WATER) {
        dao.insertLog(
            IntakeLog(
                amountMl = amountMl,
                date = today(),
                note = note,
                drinkType = type.key,
                hydrationMl = type.hydration(amountMl),
            )
        )
    }

    suspend fun deleteLog(log: IntakeLog) = dao.deleteLog(log)

    /** Today's credited total (non-observing) — used by the home-screen widget. */
    suspend fun todayTotal(): Int = dao.getTotalForDate(today())

    /**
     * Calculates current streak: consecutive days (ending today or yesterday)
     * where the goal was met.
     */
    suspend fun calculateStreak(goalMl: Int): Int {
        val fromDate = LocalDate.now().minusDays(365).format(dateFormatter)
        val metDays = dao.getDaysGoalMet(fromDate, goalMl)
            .map { it.date }
            .toSortedSet(reverseOrder())

        if (metDays.isEmpty()) return 0

        var streak = 0
        var check = LocalDate.now()

        // Allow streak if today is not done yet (count from yesterday)
        if (!metDays.contains(check.format(dateFormatter))) {
            check = check.minusDays(1)
        }

        while (metDays.contains(check.format(dateFormatter))) {
            streak++
            check = check.minusDays(1)
        }
        return streak
    }

    /**
     * Permanently deletes all user data from the database.
     * Called from Settings → "Delete all my data".
     */
    suspend fun deleteAllData() {
        dao.deleteAllLogs()
        dao.deleteProfile()
        // Everything the reminder policy learned is personal data too.
        dao.deleteAllArmStats()
        dao.deleteAllDecisions()
    }

    /**
     * Full "Delete all my data": the local database, every online setting
     * (city, cached weather, sync flags), and — if cloud sync was used — the
     * cloud copy. Without the last two, the next launch would see sync still
     * enabled and pull the deleted history straight back down.
     *
     * @return false if the cloud copy couldn't be removed (e.g. offline);
     *         local data is deleted regardless.
     */
    suspend fun deleteEverything(context: Context): Boolean {
        val store = SettingsStore.get(context)
        val hadCloud = store.cloudSyncEnabled
        // Stop syncing *before* anything else so nothing can re-upload.
        store.cloudSyncEnabled = false

        deleteAllData()
        store.clearAll()

        return if (hadCloud || CloudSync().isAvailable()) {
            if (NetworkUtil.isOnline(context)) CloudSync().deleteRemoteDataAndSignOut()
            else !hadCloud
        } else true
    }

    // ── On-device intelligence ───────────────────────────────────────────────

    /** The goal the UI and reminders should use: adaptive if learned, else formula. */
    fun effectiveGoal(profile: UserProfile): Int =
        if (profile.smartFeaturesEnabled && profile.adaptiveGoalMl > 0) profile.adaptiveGoalMl
        else profile.dailyGoalMl

    /**
     * Re-learns the adaptive goal from the last 28 completed days and persists
     * it on the profile when it changes. Cheap — call on app open.
     */
    suspend fun refreshAdaptiveGoal() {
        val profile = dao.getProfile() ?: return
        if (!profile.smartFeaturesEnabled) return
        val history = completedDayRecords(28)
        val refined = AdaptiveGoalEngine.refine(profile.dailyGoalMl, history)
        val newValue = if (refined == profile.dailyGoalMl) 0 else refined
        if (newValue != profile.adaptiveGoalMl) {
            dao.upsertProfile(profile.copy(adaptiveGoalMl = newValue, updatedAt = System.currentTimeMillis()))
        }
    }

    /**
     * Reminder decision for "now".
     *
     * When the learning policy is enabled this runs the JITAI pipeline:
     * hard availability gate → contextual bandit (Thompson sampling) → decision
     * recorded for later reward attribution. Otherwise it falls back to the
     * original rule-based engine.
     */
    suspend fun reminderDecision(): ReminderDecision {
        val profile = dao.getProfile()
            ?: return ReminderDecision(false, reason = "no profile")

        val fromDate = LocalDate.now().minusDays(28).format(dateFormatter)
        val model = DrinkingPatternModel(
            dao.getLogsSince(fromDate).map { LogPoint(it.timestampMs, it.hydrationMl) }
        )
        val lastLog = dao.getLastLogTime()
        val minutesSince = lastLog?.let { (System.currentTimeMillis() - it) / 60_000 }
        val consumed = dao.getTotalForDate(today())
        val goal = effectiveGoal(profile)

        if (!profile.banditEnabled) {
            return SmartReminderEngine.decide(
                hourOfDay = LocalTime.now().hour,
                minutesSinceLastLog = minutesSince,
                todayTotalMl = consumed,
                goalMl = goal,
                model = model,
            )
        }

        // Settle any decisions whose attribution window has closed, so the
        // policy learns from them before choosing again.
        resolvePendingRewards()

        val hour = LocalTime.now().hour

        // ── 1. Availability gate (hard rules; bandit never explores here) ──
        val gate = when {
            consumed >= goal ->
                Availability.no("goal already met")
            minutesSince != null && minutesSince < RECENT_LOG_MINUTES ->
                Availability.no("logged $minutesSince min ago")
            model.trainingDays >= MIN_HISTORY_DAYS && model.isQuietHour(hour) ->
                Availability.no("learned quiet hour")
            model.trainingDays < MIN_HISTORY_DAYS && (hour >= 23 || hour <= 5) ->
                Availability.no("cold-start quiet hours")
            else -> Availability.available()
        }
        if (!gate.available) return ReminderDecision(false, reason = gate.reason)

        // ── 2. Contextual bandit chooses among the available actions ──
        val expectedByNow = model.cumulativeShare(hour) * goal
        val ctx = BanditContext.of(
            hour = hour,
            consumedMl = consumed,
            expectedByNowMl = expectedByNow,
            weekend = LocalDate.now().dayOfWeek.value >= 6,
        )
        val posteriors = loadPosteriors(ctx.key)
        val arm = ThompsonSamplingPolicy().selectArm(posteriors)

        // ── 3. Record the decision so its reward can be attributed later ──
        dao.insertDecision(
            BanditDecisionRecord(
                contextKey = ctx.key,
                arm = arm.id,
                decidedAtMs = System.currentTimeMillis(),
                consumedAtDecisionMl = consumed,
            )
        )

        if (!arm.isNotification) {
            return ReminderDecision(false, reason = "policy chose to stay silent (${ctx.key})")
        }

        val deficit = ((expectedByNow - consumed) / 50.0).roundToInt() * 50
        val message = when {
            arm == BanditArm.MOTIVATIONAL && deficit >= 200 ->
                "You're about $deficit ml behind your usual pace — you've got this! 💪"
            arm == BanditArm.MOTIVATIONAL ->
                "A glass now keeps your streak alive — let's go! 🔥"
            deficit >= 200 ->
                "You're about $deficit ml behind your usual pace."
            else ->
                "A quick glass now keeps you on track for today's goal."
        }
        return ReminderDecision(true, message, reason = "bandit:${arm.name} @ ${ctx.key}")
    }

    /** Posteriors for every arm in a context, creating uniform priors as needed. */
    private suspend fun loadPosteriors(contextKey: String): List<ArmPosterior> {
        val stored = dao.getArmStats(contextKey).associateBy { it.arm }
        return BanditArm.entries.map { arm ->
            val s = stored[arm.id]
            ArmPosterior(
                contextKey = contextKey,
                arm = arm,
                alpha = s?.alpha ?: 1.0,
                beta = s?.beta ?: 1.0,
            )
        }
    }

    /**
     * Closes the loop: for every decision whose attribution window has elapsed,
     * checks whether the user actually drank and folds the reward into that
     * (context, arm) posterior. This is what makes the policy improve.
     */
    suspend fun resolvePendingRewards() {
        val now = System.currentTimeMillis()
        val cutoff = now - ATTRIBUTION_WINDOW_MS
        val pending = dao.getUnresolvedDecisions(cutoff)
        if (pending.isEmpty()) return

        for (d in pending) {
            val drank = dao.getHydrationBetween(
                sinceMs = d.decidedAtMs,
                untilMs = d.decidedAtMs + ATTRIBUTION_WINDOW_MS,
            ) > 0
            val arm = BanditArm.from(d.arm)
            val reward = Reward.of(arm, drank)

            val current = dao.getArmStats(d.contextKey).firstOrNull { it.arm == d.arm }
                ?: BanditArmStat(contextKey = d.contextKey, arm = d.arm)
            val updated = ArmPosterior(d.contextKey, arm, current.alpha, current.beta)
                .updated(reward)

            dao.upsertArmStat(
                current.copy(alpha = updated.alpha, beta = updated.beta)
            )
            dao.resolveDecision(d.id, reward)
        }
    }

    /**
     * Runs one federated round for the reminder policy, if the user opted in,
     * Firebase is configured and the device is online. Shares only privatised
     * pseudo-counts — never raw intake data.
     */
    suspend fun runFederatedRound(
        context: Context,
        /** Bypass the stored flag when the user just toggled it on (avoids a
         *  race with the profile write that enables it). */
        force: Boolean = false,
    ): FederatedPolicyClient.Outcome {
        val profile = dao.getProfile()
            ?: return FederatedPolicyClient.Outcome.Failed("No profile yet — finish onboarding first.")
        if (!force && !profile.federatedEnabled) {
            return FederatedPolicyClient.Outcome.Failed("Federated learning is switched off.")
        }
        if (!profile.banditEnabled) {
            return FederatedPolicyClient.Outcome.Failed(
                "The learning reminder policy is off, so there's no policy to share."
            )
        }
        if (!NetworkUtil.isOnline(context)) {
            return FederatedPolicyClient.Outcome.Failed("You're offline — it'll sync when you reconnect.")
        }

        // No local statistics is fine: the device downloads the population
        // model instead of uploading. That's the cold-start path.
        val local = dao.getAllArmStats()

        return FederatedPolicyClient().runRound(local) { merged ->
            dao.upsertArmStats(merged)
        }
    }

    /** Human-readable summary of what the reminder policy has learned so far. */
    suspend fun banditSummary(): BanditSummary {
        val stats = dao.getAllArmStats()
        val resolved = dao.getResolvedDecisionCount()
        if (stats.isEmpty()) {
            return BanditSummary(0, 0, 0.0, emptyList())
        }
        val notifications = stats.filter { BanditArm.from(it.arm).isNotification }
        val sent = notifications.sumOf { (it.alpha + it.beta - 2.0) }.roundToInt()

        // Best context/arm pairs by posterior mean, among those with real data.
        val top = stats
            .filter { it.alpha + it.beta - 2.0 >= 1.0 }
            .sortedByDescending { it.alpha / (it.alpha + it.beta) }
            .take(3)
            .map {
                BanditInsight(
                    contextKey = it.contextKey,
                    arm = BanditArm.from(it.arm),
                    successRate = it.alpha / (it.alpha + it.beta),
                    observations = (it.alpha + it.beta - 2.0).roundToInt(),
                )
            }

        val overall = stats.sumOf { it.alpha } /
            stats.sumOf { it.alpha + it.beta }.coerceAtLeast(1.0)

        return BanditSummary(
            decisionsLearned = resolved,
            notificationsSent = sent,
            overallSuccessRate = overall,
            topInsights = top,
        )
    }

    /** Weekly text insight for the History screen. */
    suspend fun weeklyInsight(): String {
        val profile = dao.getProfile()
            ?: return "Set up your profile to start seeing insights."
        val history = completedDayRecords(28)
        return InsightGenerator.weekly(
            history = history,
            goalMl = effectiveGoal(profile),
            adaptiveGoalMl = if (profile.smartFeaturesEnabled) profile.adaptiveGoalMl else null,
        )
    }

    /** Daily totals for the last [days] days, excluding today (still in progress). */
    private suspend fun completedDayRecords(days: Int): List<DayRecord> {
        val fromDate = LocalDate.now().minusDays(days.toLong()).format(dateFormatter)
        return dao.getDailyStats(fromDate)
            .filter { it.date < today() }
            .map { DayRecord(it.date, it.totalMl) }
    }

    private fun today(): String = LocalDate.now().format(dateFormatter)

    // ── Local backup (works with no account and no network) ──────────────────

    data class ImportResult(val logsAdded: Int, val profileRestored: Boolean)

    /**
     * Serialises the whole history to JSON so the user can keep their own copy.
     * Deliberately plain, readable JSON: it is *their* data, and they should be
     * able to open it. It leaves the encrypted store only because the user
     * explicitly asked for a file.
     */
    suspend fun exportBackupJson(): String {
        val root = org.json.JSONObject()
        root.put("format", "swims-backup")
        root.put("version", 1)
        root.put("exportedAt", System.currentTimeMillis())

        dao.getProfile()?.let { p ->
            root.put(
                "profile",
                org.json.JSONObject().apply {
                    put("weightKg", p.weightKg.toDouble())
                    put("ageYears", p.ageYears)
                    put("activityLevel", p.activityLevel)
                    put("dailyGoalMl", p.dailyGoalMl)
                    put("reminderIntervalHours", p.reminderIntervalHours)
                    put("remindersEnabled", p.remindersEnabled)
                    put("smartFeaturesEnabled", p.smartFeaturesEnabled)
                    put("adaptiveGoalMl", p.adaptiveGoalMl)
                    put("banditEnabled", p.banditEnabled)
                    put("federatedEnabled", p.federatedEnabled)
                    put("updatedAt", p.updatedAt)
                }
            )
        }

        val arr = org.json.JSONArray()
        dao.getAllLogs().forEach { l ->
            arr.put(
                org.json.JSONObject().apply {
                    put("amountMl", l.amountMl)
                    put("date", l.date)
                    put("timestampMs", l.timestampMs)
                    put("note", l.note ?: org.json.JSONObject.NULL)
                    put("drinkType", l.drinkType)
                    put("hydrationMl", l.hydrationMl)
                }
            )
        }
        root.put("logs", arr)
        return root.toString(2)
    }

    /**
     * Restores a backup, merging rather than replacing: logs already present
     * (matched on timestamp) are skipped, so importing twice is harmless.
     */
    suspend fun importBackupJson(json: String): ImportResult {
        val root = org.json.JSONObject(json)
        require(root.optString("format") == "swims-backup") {
            "This file isn't a SWIMS backup."
        }

        var profileRestored = false
        root.optJSONObject("profile")?.let { p ->
            val existing = dao.getProfile()
            val incomingUpdated = p.optLong("updatedAt", 0L)
            // Don't clobber a newer local profile with a stale backup.
            if (existing == null || incomingUpdated >= existing.updatedAt) {
                dao.upsertProfile(
                    UserProfile(
                        id = 1,
                        weightKg = p.optDouble("weightKg", 70.0).toFloat(),
                        ageYears = p.optInt("ageYears", 25),
                        activityLevel = p.optInt("activityLevel", 1),
                        dailyGoalMl = p.optInt("dailyGoalMl", 2450),
                        reminderIntervalHours = p.optInt("reminderIntervalHours", 2),
                        remindersEnabled = p.optBoolean("remindersEnabled", true),
                        smartFeaturesEnabled = p.optBoolean("smartFeaturesEnabled", true),
                        adaptiveGoalMl = p.optInt("adaptiveGoalMl", 0),
                        banditEnabled = p.optBoolean("banditEnabled", true),
                        federatedEnabled = p.optBoolean("federatedEnabled", false),
                        updatedAt = if (incomingUpdated > 0) incomingUpdated else System.currentTimeMillis(),
                    )
                )
                profileRestored = true
            }
        }

        val existingStamps = dao.getAllLogTimestamps().toHashSet()
        var added = 0
        val logs = root.optJSONArray("logs")
        if (logs != null) {
            for (i in 0 until logs.length()) {
                val l = logs.optJSONObject(i) ?: continue
                val ts = l.optLong("timestampMs", 0L)
                if (ts == 0L || ts in existingStamps) continue
                val amount = l.optInt("amountMl", 0)
                val date = l.optString("date", "")
                if (amount <= 0 || date.isBlank()) continue
                dao.insertLog(
                    IntakeLog(
                        id = 0,
                        amountMl = amount,
                        date = date,
                        timestampMs = ts,
                        note = if (l.isNull("note")) null else l.optString("note"),
                        drinkType = l.optString("drinkType", "water"),
                        hydrationMl = l.optInt("hydrationMl", amount),
                    )
                )
                existingStamps.add(ts)
                added++
            }
        }
        return ImportResult(added, profileRestored)
    }

    // ── Ask-SWIMS Q&A data accessors ─────────────────────────────────────────

    /** Completed-day totals for the last [days] days (today excluded). */
    suspend fun recentDailyTotals(days: Int): List<DayRecord> = completedDayRecords(days)

    /** Per-drink-type usage over the whole history. */
    suspend fun typeStats() = dao.getTypeStats()

    /** The 2–3 hours of day this user drinks the most, from the pattern model. */
    suspend fun peakDrinkingHours(): List<Int> {
        val fromDate = LocalDate.now().minusDays(28).format(dateFormatter)
        val model = DrinkingPatternModel(
            dao.getLogsSince(fromDate).map { LogPoint(it.timestampMs, it.hydrationMl) }
        )
        if (model.trainingDays < 3) return emptyList()
        return (0..23).sortedByDescending { model.hourShare(it) }.take(3).sorted()
    }

    suspend fun currentStreak(): Int {
        val profile = dao.getProfile() ?: return 0
        return calculateStreak(effectiveGoal(profile))
    }

    // ── Achievements & heatmap ───────────────────────────────────────────────

    /** All badges with unlocked state, computed from the full history. */
    suspend fun achievements(): List<com.swims.app.ml.Achievement> {
        val profile = dao.getProfile()
        val goal = profile?.let { effectiveGoal(it) } ?: 2500
        val from = LocalDate.now().minusDays(365).format(dateFormatter)
        val metDates = dao.getDaysGoalMet(from, goal).map { it.date }.toSet()
        return com.swims.app.ml.AchievementEngine.compute(dao.getAllLogs(), metDates)
    }

    /** dayOfMonth → fraction of goal reached, for the given month. */
    suspend fun monthRatios(year: Int, monthValue: Int): Map<Int, Float> {
        val profile = dao.getProfile()
        val goal = (profile?.let { effectiveGoal(it) } ?: 2500).coerceAtLeast(1)
        val ym = java.time.YearMonth.of(year, monthValue)
        val from = ym.atDay(1).format(dateFormatter)
        val to = ym.atEndOfMonth().format(dateFormatter)
        return dao.getDailyStats(from)
            .filter { it.date <= to }
            .associate { LocalDate.parse(it.date).dayOfMonth to it.totalMl.toFloat() / goal }
    }

    // ── Cloud sync (optional; inert unless Firebase is configured) ────────────

    /**
     * Two-way sync with the cloud when it's enabled, online, and configured.
     * Safe to call anytime — it silently no-ops otherwise. Push local → pull
     * remote → merge (append-only logs by timestamp; profile last-write-wins).
     */
    suspend fun syncIfEnabled(context: Context): Boolean {
        val store = SettingsStore.get(context)
        if (!store.cloudSyncEnabled) return false
        if (!NetworkUtil.isOnline(context)) return false

        val cloud = CloudSync()
        if (!cloud.isAvailable()) return false
        val uid = cloud.ensureSignedIn() ?: return false

        return try {
            // Push local state
            getProfile()?.let { cloud.pushProfile(uid, it) }
            cloud.pushLogs(uid, dao.getAllLogs())

            // Pull + merge remote logs we don't already have
            val existing = dao.getAllLogTimestamps().toHashSet()
            cloud.pullLogs(uid)
                .filter { it.timestampMs !in existing }
                .forEach { dao.insertLog(it.copy(id = 0)) }

            // Merge remote profile if it's newer
            val local = dao.getProfile()
            cloud.pullProfile(uid)?.let { remote ->
                if (local == null || remote.updatedAt > local.updatedAt) {
                    dao.upsertProfile(remote.copy(id = 1))
                }
            }

            store.lastSyncAtMs = System.currentTimeMillis()
            true
        } catch (_: Exception) {
            // Optional cloud sync must never interrupt local-first app usage.
            false
        }
    }
}
