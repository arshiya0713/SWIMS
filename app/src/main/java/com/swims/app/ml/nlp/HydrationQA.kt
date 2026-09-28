package com.swims.app.ml.nlp

import com.swims.app.data.model.DrinkType
import com.swims.app.data.repository.SwimsRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * "Ask SWIMS" — natural-language Q&A over the user's own hydration history.
 *
 * Intent detection is keyword-based and the answers are composed from live
 * database queries, so every reply is grounded in the user's real data and
 * computed entirely on-device. No network, no external model, no hallucination:
 * if the data isn't there, it says so.
 */
object HydrationQA {

    private val dateFmt = DateTimeFormatter.ISO_LOCAL_DATE

    suspend fun answer(rawQuestion: String, repo: SwimsRepository): String {
        val q = rawQuestion.lowercase().trim()
        if (q.isBlank()) return help()

        return when {
            // Today
            q.containsAny("today", "so far", "right now", "currently") && !q.contains("yesterday") ->
                todayAnswer(repo)

            // Yesterday
            q.contains("yesterday") -> yesterdayAnswer(repo)

            // Streak
            q.containsAny("streak", "in a row", "consecutive") -> {
                val s = repo.currentStreak()
                when {
                    s == 0 -> "No active streak right now — hit today's goal to start one! 🔥"
                    s == 1 -> "🔥 Your streak is 1 day. Meet today's goal to make it 2!"
                    else -> "🔥 You're on a $s-day streak. Keep it alive today!"
                }
            }

            // Peak drinking time
            q.containsAny("when do i", "what time", "peak", "most often", "usually drink") ->
                peakAnswer(repo)

            // Per-drink-type questions
            DrinkType.entries.any { q.contains(it.label.lowercase()) } ->
                typeAnswer(q, repo)

            // Weekly summary
            q.containsAny("week", "7 day", "seven day") -> periodAnswer(repo, 7, "week")

            // Monthly summary
            q.containsAny("month", "30 day", "thirty day") -> periodAnswer(repo, 30, "month")

            // Goal
            q.containsAny("goal", "target", "how much should") -> goalAnswer(repo)

            // Achievements
            q.containsAny("achievement", "badge", "trophy", "unlock") -> {
                val a = repo.achievements()
                val unlocked = a.filter { it.unlocked }
                if (unlocked.isEmpty())
                    "No badges yet — your first one (💧 First Drop) unlocks with your very first log!"
                else
                    "🏆 ${unlocked.size} of ${a.size} badges unlocked: " +
                        unlocked.joinToString(", ") { "${it.emoji} ${it.title}" } +
                        ". Next up: ${a.firstOrNull { !it.unlocked }?.let { "${it.emoji} ${it.title} — ${it.description}" } ?: "all done!"}"
            }

            // Reminders / AI policy
            q.containsAny("remind", "notification", "nudge", "bandit", "policy") -> {
                val s = repo.banditSummary()
                if (s.decisionsLearned == 0)
                    "The reminder AI is still warming up — it needs a few reminder cycles before it has learned anything about you."
                else buildString {
                    append("The reminder policy has learned from ${s.decisionsLearned} decisions")
                    s.topInsights.firstOrNull()?.let {
                        append(". Its best finding: ${it.arm.label.lowercase()} works ${(it.successRate * 100).toInt()}% of the time when ${it.readableContext.lowercase()}")
                    }
                    append(".")
                }
            }

            // Best day
            q.containsAny("best day", "record", "most i") -> {
                val days = repo.recentDailyTotals(90)
                val best = days.maxByOrNull { it.totalMl }
                if (best == null) "Not enough history yet — log for a few days and ask me again!"
                else {
                    val d = LocalDate.parse(best.date)
                    "Your best day in the last 3 months was ${d.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }} ${d.dayOfMonth}/${d.monthValue} — ${best.totalMl} ml! 🐋"
                }
            }

            else -> help()
        }
    }

    // ── Answer builders ──────────────────────────────────────────────────────

    private suspend fun todayAnswer(repo: SwimsRepository): String {
        val profile = repo.getProfile() ?: return "Set up your profile first!"
        val total = repo.todayTotal()
        val goal = repo.effectiveGoal(profile)
        val pct = if (goal > 0) (total * 100 / goal) else 0
        return when {
            total == 0 -> "Nothing logged yet today. Your goal is $goal ml — time for a first glass! 💧"
            total >= goal -> "You've had $total ml today — goal of $goal ml smashed! 🎉"
            else -> "So far today: $total ml of your $goal ml goal ($pct%). ${goal - total} ml to go."
        }
    }

    private suspend fun yesterdayAnswer(repo: SwimsRepository): String {
        val yesterday = LocalDate.now().minusDays(1).format(dateFmt)
        val rec = repo.recentDailyTotals(3).firstOrNull { it.date == yesterday }
        val profile = repo.getProfile()
        val goal = profile?.let { repo.effectiveGoal(it) } ?: 0
        return when {
            rec == null || rec.totalMl == 0 -> "Nothing was logged yesterday."
            goal > 0 && rec.totalMl >= goal -> "Yesterday: ${rec.totalMl} ml — goal met! ✅"
            goal > 0 -> "Yesterday: ${rec.totalMl} ml, which was ${goal - rec.totalMl} ml short of your $goal ml goal."
            else -> "Yesterday you drank ${rec.totalMl} ml."
        }
    }

    private suspend fun peakAnswer(repo: SwimsRepository): String {
        val peaks = repo.peakDrinkingHours()
        if (peaks.isEmpty()) return "I need a few days of logs before I can spot your drinking pattern."
        val ranges = peaks.joinToString(", ") { h -> "${fmtHour(h)}–${fmtHour(h + 1)}" }
        return "You drink the most around $ranges. The smart reminders already plan around this. ⏰"
    }

    private suspend fun typeAnswer(q: String, repo: SwimsRepository): String {
        val type = DrinkType.entries.first { q.contains(it.label.lowercase()) }
        val stat = repo.typeStats().firstOrNull { it.drinkType == type.key }
            ?: return "You haven't logged any ${type.label.lowercase()} yet."
        val litres = "%.1f".format(stat.totalMl / 1000.0)
        val credited = "%.1f".format(stat.totalHydrationMl / 1000.0)
        val factorNote = if (type.factor < 1.0)
            " (counted as $credited L after the ${(type.factor * 100).toInt()}% hydration factor)"
        else ""
        return "${type.emoji} ${type.label}: logged ${stat.count} times, $litres L total$factorNote."
    }

    private suspend fun periodAnswer(repo: SwimsRepository, days: Int, label: String): String {
        val recs = repo.recentDailyTotals(days).filter { it.totalMl > 0 }
        if (recs.isEmpty()) return "No logs in the last $label yet — let's change that! 💧"
        val avg = recs.sumOf { it.totalMl } / recs.size
        val total = recs.sumOf { it.totalMl }
        val best = recs.maxByOrNull { it.totalMl }!!
        val bestDay = LocalDate.parse(best.date).dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
        return "Last $label: ${"%.1f".format(total / 1000.0)} L across ${recs.size} logged days " +
            "(avg $avg ml/day). Best day was $bestDay with ${best.totalMl} ml."
    }

    private suspend fun goalAnswer(repo: SwimsRepository): String {
        val p = repo.getProfile() ?: return "Set up your profile to get a personalised goal."
        val goal = repo.effectiveGoal(p)
        val how = if (p.smartFeaturesEnabled && p.adaptiveGoalMl > 0)
            "tuned by the on-device AI from your own history (formula said ${p.dailyGoalMl} ml)"
        else
            "from the formula: weight × 35 ml, adjusted for age and activity"
        return "Your daily goal is $goal ml — $how. Hot days can add up to 800 ml on top. 🌡️"
    }

    private fun fmtHour(h: Int): String {
        val hh = ((h % 24) + 24) % 24
        return when {
            hh == 0 -> "12am"; hh < 12 -> "${hh}am"; hh == 12 -> "12pm"; else -> "${hh - 12}pm"
        }
    }

    private fun help(): String =
        "I can answer things like:\n" +
            "• \"How much have I drunk today?\"\n" +
            "• \"What about this week?\" · \"…this month?\"\n" +
            "• \"What's my streak?\" · \"What's my best day?\"\n" +
            "• \"When do I usually drink?\"\n" +
            "• \"How much coffee do I drink?\"\n" +
            "• \"What's my goal?\" · \"Show my badges\"\n" +
            "• \"What have the reminders learned?\""

    private fun String.containsAny(vararg keys: String) = keys.any { contains(it) }
}
