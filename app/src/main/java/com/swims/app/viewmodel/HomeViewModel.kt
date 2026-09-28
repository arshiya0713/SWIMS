package com.swims.app.viewmodel

import android.app.Application
import androidx.lifecycle.*
import com.swims.app.data.db.SwimsDatabase
import com.swims.app.data.model.DrinkType
import com.swims.app.data.model.IntakeLog
import com.swims.app.data.model.UserProfile
import com.swims.app.data.repository.SwimsRepository
import com.swims.app.ml.HydrationSafety
import com.swims.app.network.WeatherManager
import com.swims.app.widget.SwimsWidgetProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SwimsRepository(SwimsDatabase.getInstance(app).dao())
    private val weather = WeatherManager(app)

    val profile = repo.observeProfile()
    val todayLogs = repo.observeLogsForToday()
    val todayTotalMl = repo.observeTotalForToday()

    /** Today's goal including any weather bonus; falls back to the base goal. */
    private val _todayGoal = MutableLiveData<Int>()
    val todayGoal: LiveData<Int> = _todayGoal

    /** Short weather line for the header, or null to hide it. */
    private val _weatherChip = MutableLiveData<String?>()
    val weatherChip: LiveData<String?> = _weatherChip

    init {
        // Re-learn the adaptive goal from history each time the screen is created.
        // Updates the profile row, so observers refresh automatically.
        viewModelScope.launch { repo.refreshAdaptiveGoal() }
        // Pull remote changes if cloud sync is on (no-op when off/offline).
        viewModelScope.launch { repo.syncIfEnabled(app) }
    }

    /** Adaptive goal when learned & enabled, otherwise the formula goal. */
    fun effectiveGoal(profile: UserProfile): Int = repo.effectiveGoal(profile)

    /** True when the displayed goal is the learned one (not the formula). */
    fun isSmartGoal(profile: UserProfile): Boolean =
        repo.effectiveGoal(profile) != profile.dailyGoalMl

    /** Fetch weather (or use cache offline) and publish the adjusted goal + chip. */
    fun refreshWeatherGoal(baseGoal: Int) {
        viewModelScope.launch {
            val r = weather.todayGoal(baseGoal)
            _todayGoal.postValue(r.goalMl)
            _weatherChip.postValue(buildWeatherChip(r))
        }
    }

    private fun buildWeatherChip(r: WeatherManager.GoalResult): String? {
        val temp = r.tempC ?: return null
        val t = temp.roundToInt()
        val where = r.city ?: "your area"
        val offline = if (r.stale) " (offline)" else ""
        return when {
            r.bonusMl > 0 ->
                "🌡️ $t°C in $where$offline · +${r.bonusMl} ml today to beat the heat"
            t <= 0 ->
                "❄️ $t°C in $where$offline · freezing! Warm drinks count too ☕"
            t <= 12 ->
                "🧣 $t°C in $where$offline · chilly — don't forget to sip"
            else ->
                "🌡️ $t°C in $where$offline · comfortable — normal goal"
        }
    }

    /** Over-hydration state for today; NORMAL unless the user is well past goal. */
    private val _safety = MutableLiveData(HydrationSafety.Check(HydrationSafety.Level.NORMAL))
    val safety: LiveData<HydrationSafety.Check> = _safety

    private var safetyJob: Job? = null

    /**
     * Re-evaluates the over-hydration state.
     *
     * Three separate observers can drive a re-render in quick succession, so
     * without cancelling the previous request several DB reads race and the
     * LAST one to finish wins — which is not necessarily the most recent one.
     * Cancelling makes the newest request authoritative.
     */
    fun refreshSafety(goalMl: Int) {
        safetyJob?.cancel()
        safetyJob = viewModelScope.launch {
            val result = repo.safetyCheck(goalMl)
            ensureActive()               // a newer request superseded this one
            _safety.postValue(result)
        }
    }

    private val _streak = MutableLiveData(0)
    val streak: LiveData<Int> = _streak

    fun refreshStreak(goalMl: Int) {
        viewModelScope.launch {
            _streak.postValue(repo.calculateStreak(goalMl))
        }
    }

    fun logIntake(amountMl: Int, note: String? = null, type: DrinkType = DrinkType.WATER) {
        viewModelScope.launch {
            repo.logIntake(amountMl, note, type)
            SwimsWidgetProvider.refresh(getApplication())
            repo.syncIfEnabled(getApplication())   // best-effort; no-op when off/offline
        }
    }

    fun deleteLog(log: IntakeLog) {
        viewModelScope.launch {
            repo.deleteLog(log)
            SwimsWidgetProvider.refresh(getApplication())
        }
    }

    /** Percent of daily goal, capped at 100 */
    fun goalPercent(totalMl: Int, goalMl: Int): Int =
        if (goalMl <= 0) 0 else ((totalMl.toFloat() / goalMl) * 100).toInt().coerceIn(0, 100)
}
