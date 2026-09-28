package com.swims.app.ui.onboarding

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.swims.app.data.db.SwimsDatabase
import com.swims.app.data.repository.SwimsRepository
import com.swims.app.databinding.ActivityOnboardingBinding
import com.swims.app.ui.home.MainActivity
import com.swims.app.util.ReminderScheduler
import com.swims.app.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private val vm: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Check if profile exists — skip onboarding if so
        lifecycleScope.launch {
            val repo = SwimsRepository(SwimsDatabase.getInstance(applicationContext).dao())
            val hasProfile = withContext(Dispatchers.IO) { repo.getProfile() != null }
            if (hasProfile) {
                goToMain()
                return@launch
            }
        }

        // Live goal preview when sliders/fields change
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateGoalPreview() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        }
        binding.etWeight.addTextChangedListener(watcher)
        binding.etAge.addTextChangedListener(watcher)
        binding.sliderActivity.addOnChangeListener { _, _, _ -> updateGoalPreview() }

        binding.btnGetStarted.setOnClickListener {
            val weightText = binding.etWeight.text.toString()
            val ageText = binding.etAge.text.toString()

            val weight = weightText.toFloatOrNull()
            val age = ageText.toIntOrNull()

            when {
                weight == null || weight < 20 || weight > 300 ->
                    Toast.makeText(this, "Enter a valid weight (20–300 kg).", Toast.LENGTH_SHORT).show()
                age == null || age < 5 || age > 110 ->
                    Toast.makeText(this, "Enter a valid age (5–110).", Toast.LENGTH_SHORT).show()
                else -> {
                    val activityLevel = binding.sliderActivity.value.toInt()
                    binding.btnGetStarted.isEnabled = false
                    vm.saveProfile(weight, age, activityLevel, 2, true) {
                        ReminderScheduler.schedule(applicationContext, 2)
                        goToMain()
                    }
                }
            }
        }

        updateGoalPreview()
    }

    private fun updateGoalPreview() {
        val weight = binding.etWeight.text.toString().toFloatOrNull() ?: 70f
        val age = binding.etAge.text.toString().toIntOrNull() ?: 25
        val activity = binding.sliderActivity.value.toInt()
        val goal = vm.previewGoal(weight, age, activity)
        binding.tvGoalPreview.text = "$goal ml"
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
