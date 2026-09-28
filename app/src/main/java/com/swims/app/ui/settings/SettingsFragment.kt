package com.swims.app.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.swims.app.databinding.DialogAccountBinding
import com.swims.app.databinding.FragmentSettingsBinding
import com.swims.app.util.ReminderScheduler
import com.swims.app.viewmodel.SettingsViewModel

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val vm: SettingsViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Populate fields from saved profile
        vm.profile.observe(viewLifecycleOwner) { profile ->
            if (profile != null) {
                binding.etWeight.setText(profile.weightKg.toString())
                binding.etAge.setText(profile.ageYears.toString())
                binding.sliderActivity.value = profile.activityLevel.toFloat()
                binding.sliderReminder.value = profile.reminderIntervalHours.toFloat()
                binding.switchReminders.isChecked = profile.remindersEnabled
                binding.switchSmart.isChecked = profile.smartFeaturesEnabled
                updateGoalPreview()
            }
        }

        // Live goal preview
        listOf(binding.etWeight, binding.etAge).forEach { et ->
            et.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateGoalPreview() }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        binding.sliderActivity.addOnChangeListener { _, _, _ -> updateGoalPreview() }

        // Save
        binding.btnSave.setOnClickListener {
            val weight = binding.etWeight.text.toString().toFloatOrNull()
            val age = binding.etAge.text.toString().toIntOrNull()
            if (weight == null || age == null) {
                Toast.makeText(requireContext(), "Enter valid weight and age.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val activityLevel = binding.sliderActivity.value.toInt()
            val interval = binding.sliderReminder.value.toInt()
            val remindersOn = binding.switchReminders.isChecked
            val smartOn = binding.switchSmart.isChecked

            vm.saveProfile(weight, age, activityLevel, interval, remindersOn, smartOn)

            if (remindersOn) {
                ReminderScheduler.schedule(requireContext(), interval)
            } else {
                ReminderScheduler.cancel(requireContext())
            }
            Toast.makeText(requireContext(), "Settings saved.", Toast.LENGTH_SHORT).show()
        }

        setupAccountCard()
        setupAiCard()
        setupOnlineCard()

        // Delete all data — confirmation required
        binding.btnDeleteData.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Delete all data?")
                .setMessage(
                    "This permanently deletes your profile, every intake log, what the reminder AI " +
                        "has learned, and your saved city — from this device and from your cloud " +
                        "backup if you use one. This cannot be undone."
                )
                .setPositiveButton("Delete") { _, _ ->
                    val appCtx = requireContext().applicationContext
                    ReminderScheduler.cancel(appCtx)
                    vm.deleteAllData { cloudCleared ->
                        com.swims.app.widget.SwimsWidgetProvider.refresh(appCtx)
                        Toast.makeText(
                            appCtx,
                            if (cloudCleared) "All data deleted."
                            else "Deleted from this device. Your cloud copy couldn't be removed (offline) — cloud sync is now off.",
                            Toast.LENGTH_LONG,
                        ).show()
                        // No profile any more → start fresh at onboarding,
                        // with the back stack cleared so Back can't return here.
                        val intent = android.content.Intent(
                            appCtx, com.swims.app.ui.onboarding.OnboardingActivity::class.java
                        ).addFlags(
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                        )
                        appCtx.startActivity(intent)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ── Account & backup ─────────────────────────────────────────────────────

    /** Pending export payload, held while the user picks a destination. */
    private var pendingExportJson: String? = null

    private val createBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val json = pendingExportJson
        pendingExportJson = null
        if (uri == null || json == null) return@registerForActivityResult
        val ok = runCatching {
            requireContext().contentResolver.openOutputStream(uri)?.use {
                it.write(json.toByteArray())
            }
        }.isSuccess
        Toast.makeText(
            requireContext(),
            if (ok) "Backup saved ✓ Keep it somewhere safe." else "Couldn't write that file.",
            Toast.LENGTH_LONG,
        ).show()
    }

    private val openBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val json = runCatching {
            requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (json.isNullOrBlank()) {
            Toast.makeText(requireContext(), "Couldn't read that file.", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        vm.importBackup(json) { result ->
            if (!isAdded) return@importBackup
            result.fold(
                onSuccess = { r ->
                    Toast.makeText(
                        requireContext(),
                        "Restored ${r.logsAdded} log${if (r.logsAdded == 1) "" else "s"}" +
                            if (r.profileRestored) " and your profile." else ".",
                        Toast.LENGTH_LONG,
                    ).show()
                },
                onFailure = {
                    Toast.makeText(
                        requireContext(),
                        it.message ?: "That file isn't a SWIMS backup.",
                        Toast.LENGTH_LONG,
                    ).show()
                },
            )
        }
    }

    private fun setupAccountCard() {
        refreshAccountState()

        binding.btnSignIn.setOnClickListener { showAccountDialog(signUp = false) }
        binding.btnSignUp.setOnClickListener { showAccountDialog(signUp = true) }

        binding.btnSignOut.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Sign out?")
                .setMessage("Your history stays on this device — signing out only stops cloud sync.")
                .setPositiveButton("Sign out") { _, _ ->
                    vm.signOut {
                        refreshAccountState()
                        Toast.makeText(requireContext(), "Signed out. Your data is still here.", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnExport.setOnClickListener {
            vm.exportBackup { json ->
                if (!isAdded) return@exportBackup
                pendingExportJson = json
                val stamp = java.time.LocalDate.now()
                createBackupLauncher.launch("swims-backup-$stamp.json")
            }
        }

        binding.btnImport.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Restore from backup?")
                .setMessage("Logs you already have are skipped, so nothing gets duplicated.")
                .setPositiveButton("Choose file") { _, _ ->
                    openBackupLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun refreshAccountState() {
        val s = vm.accountState()
        binding.tvAccountState.text = when {
            !s.configured ->
                "Accounts aren't configured in this build. Everything still works — your data is saved on this device, and you can use the backup file below."
            s.signedIn ->
                "Signed in as ${s.email}. Your history syncs so it survives a lost or reset phone."
            else ->
                "You're using SWIMS without an account — everything is stored only on this device. Sign in if you'd like your history backed up and available on other devices."
        }
        binding.llSignedOut.visibility = if (s.signedIn) View.GONE else View.VISIBLE
        binding.btnSignOut.visibility = if (s.signedIn) View.VISIBLE else View.GONE
    }

    private fun showAccountDialog(signUp: Boolean) {
        if (!vm.accountState().configured) {
            Toast.makeText(
                requireContext(),
                "Accounts need a Firebase config — see README. Use the backup file to save your data meanwhile.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        val d = DialogAccountBinding.inflate(layoutInflater)
        d.tvAccountBlurb.text =
            if (signUp) "Create an account so your history is backed up and follows you to a new phone."
            else "Sign in to restore your history on this device."

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (signUp) "Create account" else "Sign in")
            .setView(d.root)
            .setPositiveButton(if (signUp) "Create" else "Sign in", null)
            .setNegativeButton("Cancel", null)
            .create()

        d.tvForgot.setOnClickListener {
            val email = d.etEmail.text.toString()
            vm.resetPassword(email) { r ->
                if (!isAdded) return@resetPassword
                val msg = when (r) {
                    is com.swims.app.sync.AccountManager.Result.Success ->
                        "Password reset email sent to ${r.email}."
                    is com.swims.app.sync.AccountManager.Result.Failure -> r.message
                }
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            }
        }

        dialog.setOnShowListener {
            // Keep the dialog open on failure so the user can correct and retry.
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val email = d.etEmail.text.toString()
                val password = d.etPassword.text.toString()
                d.tvAccountStatus.visibility = View.GONE

                val callback: (com.swims.app.sync.AccountManager.Result) -> Unit = { r ->
                    if (isAdded) when (r) {
                        is com.swims.app.sync.AccountManager.Result.Success -> {
                            dialog.dismiss()
                            refreshAccountState()
                            Toast.makeText(
                                requireContext(),
                                "Signed in as ${r.email} — your data will sync from now on.",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        is com.swims.app.sync.AccountManager.Result.Failure -> {
                            d.tvAccountStatus.text = r.message
                            d.tvAccountStatus.visibility = View.VISIBLE
                        }
                    }
                }
                if (signUp) vm.signUp(email, password, callback)
                else vm.signIn(email, password, callback)
            }
        }
        dialog.show()
    }

    /** Adaptive-AI card: learning reminder policy + federated learning. */
    private fun setupAiCard() {
        vm.profile.observe(viewLifecycleOwner) { p ->
            if (p != null) {
                binding.switchBandit.isChecked = p.banditEnabled
                binding.switchFederated.isChecked = p.federatedEnabled
            }
        }

        val save = {
            vm.setAiFlags(binding.switchBandit.isChecked, binding.switchFederated.isChecked)
        }
        binding.switchBandit.setOnCheckedChangeListener { _, _ -> save(); refreshBanditStats() }
        binding.switchFederated.setOnCheckedChangeListener { _, on ->
            if (on && !vm.cloudConfigured) {
                binding.switchFederated.isChecked = false
                Toast.makeText(
                    requireContext(),
                    "Federated learning needs a Firebase config — see README. Your local AI keeps working.",
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                // Chain the round *after* the flag is persisted — otherwise the
                // round can read the old profile and think it's still disabled.
                vm.setAiFlags(binding.switchBandit.isChecked, on) {
                    if (on && isAdded) {
                        Toast.makeText(requireContext(), "Joining the shared model…", Toast.LENGTH_SHORT).show()
                        vm.federateNow(force = true) { msg ->
                            if (isAdded) Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            binding.btnFederateNow.visibility = if (on) View.VISIBLE else View.GONE
        }
        binding.btnFederateNow.visibility =
            if (binding.switchFederated.isChecked) View.VISIBLE else View.GONE

        binding.btnFederateNow.setOnClickListener {
            binding.btnFederateNow.isEnabled = false
            vm.federateNow(force = true) { msg ->
                if (!isAdded) return@federateNow
                binding.btnFederateNow.isEnabled = true
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            }
        }

        refreshBanditStats()
    }

    private fun refreshBanditStats() {
        vm.loadBanditSummary { s ->
            if (!isAdded) return@loadBanditSummary
            binding.tvBanditStats.text = when {
                s.decisionsLearned == 0 ->
                    "Still learning — the policy needs a few reminder cycles before it has opinions."
                s.topInsights.isEmpty() ->
                    "Learned from ${s.decisionsLearned} decisions · ${s.notificationsSent} nudges sent"
                else -> {
                    val best = s.topInsights.first()
                    "Learned from ${s.decisionsLearned} decisions · best so far: " +
                        "${best.arm.label} when ${best.readableContext} " +
                        "(${(best.successRate * 100).toInt()}% success)"
                }
            }
        }
    }

    /** Online-features card: weather city, weather toggle, cloud sync. */
    private fun setupOnlineCard() {
        val store = vm.store

        binding.switchWeather.isChecked = store.weatherEnabled
        binding.switchWeather.setOnCheckedChangeListener { _, checked ->
            store.weatherEnabled = checked
        }

        binding.etCity.setText(store.cityName ?: "")
        updateWeatherStatus()

        binding.btnSetCity.setOnClickListener {
            val name = binding.etCity.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(requireContext(), "Type a city name first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.btnSetCity.isEnabled = false
            vm.setCity(name) { resolved ->
                binding.btnSetCity.isEnabled = true
                if (resolved != null) {
                    binding.etCity.setText(resolved)
                    Toast.makeText(requireContext(), "Weather city set to $resolved", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        "Couldn't find that city (are you online?). SWIMS keeps working offline.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                updateWeatherStatus()
            }
        }

        binding.switchSync.isChecked = store.cloudSyncEnabled && vm.cloudConfigured
        binding.switchSync.setOnCheckedChangeListener { _, checked ->
            if (checked && !vm.cloudConfigured) {
                binding.switchSync.isChecked = false
                Toast.makeText(
                    requireContext(),
                    "Cloud sync needs a Firebase config — see README → Enabling cloud sync.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                store.cloudSyncEnabled = checked
            }
            updateSyncStatus()
        }
        updateSyncStatus()

        binding.btnSyncNow.setOnClickListener {
            binding.btnSyncNow.isEnabled = false
            vm.syncNow { ok ->
                binding.btnSyncNow.isEnabled = true
                Toast.makeText(
                    requireContext(),
                    if (ok) "Synced ✓" else "Sync skipped — check it's enabled, configured and you're online.",
                    Toast.LENGTH_SHORT
                ).show()
                updateSyncStatus()
            }
        }
    }

    private fun updateWeatherStatus() {
        val store = vm.store
        binding.tvWeatherStatus.text = when {
            !store.hasLocation() -> "No city set — goal won't react to hot days yet."
            !store.lastMaxTempC.isNaN() ->
                "Using ${store.cityName}. Last fetched max: ${store.lastMaxTempC.toInt()}°C. Offline? The cached value is reused for up to 12 h."
            else -> "Using ${store.cityName}."
        }
    }

    private fun updateSyncStatus() {
        val store = vm.store
        binding.tvSyncStatus.text = when {
            !vm.cloudConfigured ->
                "Not configured in this build — data stays on-device. To enable: add google-services.json (README)."
            !store.cloudSyncEnabled -> "Off — data stays on this device only."
            store.lastSyncAtMs > 0L -> {
                val mins = (System.currentTimeMillis() - store.lastSyncAtMs) / 60_000
                "On (anonymous account). Last synced ${if (mins < 1) "just now" else "$mins min ago"}."
            }
            else -> "On — will sync when online."
        }
    }

    private fun updateGoalPreview() {
        val weight = binding.etWeight.text.toString().toFloatOrNull() ?: 70f
        val age = binding.etAge.text.toString().toIntOrNull() ?: 25
        val activity = binding.sliderActivity.value.toInt()
        val goal = vm.previewGoal(weight, age, activity)
        binding.tvGoalPreview.text = "Daily goal will be: $goal ml"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
