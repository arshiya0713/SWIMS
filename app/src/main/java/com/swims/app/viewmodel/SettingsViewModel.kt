package com.swims.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.swims.app.data.SettingsStore
import com.swims.app.data.db.SwimsDatabase
import com.swims.app.data.model.UserProfile
import com.swims.app.data.repository.SwimsRepository
import com.swims.app.ml.bandit.BanditSummary
import com.swims.app.network.WeatherManager
import com.swims.app.sync.AccountManager
import com.swims.app.sync.CloudSync
import com.swims.app.sync.FederatedPolicyClient
import com.swims.app.util.GoalCalculator
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SwimsRepository(SwimsDatabase.getInstance(app).dao())
    val profile = repo.observeProfile()

    val store: SettingsStore = SettingsStore.get(app)
    private val weather = WeatherManager(app)

    /** True when a Firebase config is baked into this build. */
    val cloudConfigured: Boolean get() = CloudSync().isAvailable()

    /** Geocodes and saves the city; calls back with the resolved name or null. */
    fun setCity(name: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val place = weather.setCity(name)
            onResult(place?.let { p ->
                if (p.country.isNotBlank()) "${p.name}, ${p.country}" else p.name
            })
        }
    }

    /** Runs a manual sync; calls back with success. */
    fun syncNow(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            onResult(repo.syncIfEnabled(getApplication()))
        }
    }

    // ── Account (optional) ───────────────────────────────────────────────────

    private val account = AccountManager()

    fun accountState(): AccountManager.AccountState = account.state()

    fun signUp(email: String, password: String, onResult: (AccountManager.Result) -> Unit) {
        viewModelScope.launch {
            val r = account.signUp(email, password)
            if (r is AccountManager.Result.Success) enableSyncAndPush()
            onResult(r)
        }
    }

    fun signIn(email: String, password: String, onResult: (AccountManager.Result) -> Unit) {
        viewModelScope.launch {
            val r = account.signIn(email, password)
            if (r is AccountManager.Result.Success) enableSyncAndPush()
            onResult(r)
        }
    }

    fun resetPassword(email: String, onResult: (AccountManager.Result) -> Unit) {
        viewModelScope.launch { onResult(account.resetPassword(email)) }
    }

    fun signOut(onDone: () -> Unit) {
        account.signOut()
        // Signing out stops syncing but never deletes local history.
        store.cloudSyncEnabled = false
        onDone()
    }

    /** Signing in implies "keep my data safe", so turn sync on and push now. */
    private suspend fun enableSyncAndPush() {
        store.cloudSyncEnabled = true
        runCatching { repo.syncIfEnabled(getApplication()) }
    }

    // ── Backup file (no account required) ────────────────────────────────────

    fun exportBackup(onReady: (String) -> Unit) {
        viewModelScope.launch { onReady(repo.exportBackupJson()) }
    }

    fun importBackup(json: String, onResult: (Result<SwimsRepository.ImportResult>) -> Unit) {
        viewModelScope.launch {
            onResult(runCatching { repo.importBackupJson(json) })
        }
    }

    // ── Adaptive AI ──────────────────────────────────────────────────────────

    /** Persists the AI opt-in flags immediately (no Save button needed). */
    fun setAiFlags(banditOn: Boolean, federatedOn: Boolean, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            val existing = repo.getProfile() ?: return@launch
            repo.saveProfile(
                existing.copy(
                    banditEnabled = banditOn,
                    federatedEnabled = federatedOn,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            onSaved()
        }
    }

    /** Summary of what the reminder policy has learned so far. */
    fun loadBanditSummary(onResult: (BanditSummary) -> Unit) {
        viewModelScope.launch { onResult(repo.banditSummary()) }
    }

    /**
     * Runs one federated round on demand and reports what actually happened.
     * [force] skips the stored-flag check, for the moment the user flips the
     * toggle on and the profile write may not have landed yet.
     */
    fun federateNow(force: Boolean = false, onResult: (String) -> Unit) {
        viewModelScope.launch {
            when (val o = repo.runFederatedRound(getApplication(), force)) {
                is FederatedPolicyClient.Outcome.Failed -> onResult(o.reason)
                is FederatedPolicyClient.Outcome.Completed -> {
                    val r = o.result
                    onResult(
                        when {
                            r.downloadedContexts == 0 ->
                                "Shared ${r.uploadedContexts} contexts — you're the first contributor, so there's no global model to merge yet."
                            r.uploadedContexts == 0 ->
                                "Nothing local to share yet — merged ${r.downloadedContexts} contexts from the shared model to start you off."
                            else ->
                                "Shared ${r.uploadedContexts} · merged ${r.downloadedContexts} contexts from ${r.contributors} contributions."
                        }
                    )
                }
            }
        }
    }

    /**
     * Saves the profile, then invokes [onSaved] on the main thread.
     * Callers that navigate away (e.g. onboarding) must wait for [onSaved] —
     * finishing the activity cancels viewModelScope and would abort the write.
     */
    fun saveProfile(
        weightKg: Float,
        ageYears: Int,
        activityLevel: Int,
        reminderIntervalHours: Int,
        remindersEnabled: Boolean,
        smartFeaturesEnabled: Boolean = true,
        onSaved: () -> Unit = {}
    ) {
        val goal = GoalCalculator.calculate(weightKg, ageYears, activityLevel)
        viewModelScope.launch {
            val existing = repo.getProfile()
            repo.saveProfile(
                UserProfile(
                    weightKg = weightKg,
                    ageYears = ageYears,
                    activityLevel = activityLevel,
                    dailyGoalMl = goal,
                    reminderIntervalHours = reminderIntervalHours,
                    remindersEnabled = remindersEnabled,
                    smartFeaturesEnabled = smartFeaturesEnabled,
                    // keep what's been learned unless smart features were turned off
                    adaptiveGoalMl = if (smartFeaturesEnabled) existing?.adaptiveGoalMl ?: 0 else 0,
                    // preserve AI opt-ins — they have their own switches
                    banditEnabled = existing?.banditEnabled ?: true,
                    federatedEnabled = existing?.federatedEnabled ?: false
                )
            )
            if (smartFeaturesEnabled) repo.refreshAdaptiveGoal()
            onSaved()
        }
    }

    /** Deletes everything; [onDone] receives false if the cloud copy couldn't be removed. */
    fun deleteAllData(onDone: (cloudCleared: Boolean) -> Unit) {
        viewModelScope.launch {
            val cloudOk = repo.deleteEverything(getApplication())
            onDone(cloudOk)
        }
    }

    fun previewGoal(weightKg: Float, ageYears: Int, activityLevel: Int): Int =
        GoalCalculator.calculate(weightKg, ageYears, activityLevel)
}
