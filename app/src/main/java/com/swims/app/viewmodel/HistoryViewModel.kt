package com.swims.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.swims.app.data.SettingsStore
import com.swims.app.data.db.SwimsDatabase
import com.swims.app.data.repository.SwimsRepository
import com.swims.app.ml.Achievement
import kotlinx.coroutines.launch
import java.time.YearMonth

class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SwimsRepository(SwimsDatabase.getInstance(app).dao())
    private val store = SettingsStore.get(app)

    val weekly = repo.observeDailyStats(7)
    val monthly = repo.observeDailyStats(30)

    private val _insight = MutableLiveData<String>()
    /** Generated on-device from the user's history — see InsightGenerator */
    val insight: LiveData<String> = _insight

    private val _achievements = MutableLiveData<List<Achievement>>()
    val achievements: LiveData<List<Achievement>> = _achievements

    /** Achievements newly unlocked since last announced — for a one-time toast. */
    private val _newUnlocks = MutableLiveData<List<Achievement>>()
    val newUnlocks: LiveData<List<Achievement>> = _newUnlocks

    private val _heatmap = MutableLiveData<Pair<YearMonth, Map<Int, Float>>>()
    val heatmap: LiveData<Pair<YearMonth, Map<Int, Float>>> = _heatmap

    private var shownMonth: YearMonth = YearMonth.now()

    init {
        viewModelScope.launch { _insight.postValue(repo.weeklyInsight()) }
        refreshAchievements()
        loadMonth(shownMonth)
    }

    private fun refreshAchievements() {
        viewModelScope.launch {
            val all = repo.achievements()
            _achievements.postValue(all)

            val announced = store.announcedAchievements
            val fresh = all.filter { it.unlocked && it.id !in announced }
            if (fresh.isNotEmpty()) {
                store.announcedAchievements = announced + fresh.map { it.id }
                _newUnlocks.postValue(fresh)
            }
        }
    }

    /** Ask-SWIMS Q&A: answers computed on-device from the user's own data. */
    fun ask(question: String, onAnswer: (String) -> Unit) {
        viewModelScope.launch {
            onAnswer(com.swims.app.ml.nlp.HydrationQA.answer(question, repo))
        }
    }

    fun prevMonth() = loadMonth(shownMonth.minusMonths(1))
    fun nextMonth() {
        if (shownMonth < YearMonth.now()) loadMonth(shownMonth.plusMonths(1))
    }

    private fun loadMonth(month: YearMonth) {
        shownMonth = month
        viewModelScope.launch {
            _heatmap.postValue(month to repo.monthRatios(month.year, month.monthValue))
        }
    }
}
