package com.swims.app.ui.home

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.swims.app.R
import nl.dionsegijn.konfetti.core.Party
import nl.dionsegijn.konfetti.core.Position
import nl.dionsegijn.konfetti.core.emitter.Emitter
import java.util.concurrent.TimeUnit
import com.swims.app.data.model.DrinkType
import com.swims.app.databinding.DialogLogDrinkBinding
import com.swims.app.databinding.DialogSmartLogBinding
import com.swims.app.databinding.FragmentHomeBinding
import com.swims.app.ml.HydrationSafety
import com.swims.app.ml.nlp.DrinkTextParser
import com.swims.app.ml.vision.DrinkImageClassifier
import com.swims.app.viewmodel.HomeViewModel

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val vm: HomeViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Theme-aware water colours for the drop
        fun c(id: Int) = ContextCompat.getColor(requireContext(), id)
        binding.progressRing.setColors(
            track = c(R.color.water_track),
            waterTop = c(R.color.water_top),
            waterBottom = c(R.color.water_bottom),
            outline = c(R.color.on_gradient),
        )

        // Quick-add buttons
        val quickAmounts = listOf(
            binding.btn200 to 200,
            binding.btn350 to 350,
            binding.btn500 to 500,
            binding.btn750 to 750
        )
        quickAmounts.forEach { (btn, ml) ->
            btn.setOnClickListener { logByUser(ml) }
        }

        // Custom amount
        binding.btnCustom.setOnClickListener { showCustomDialog() }

        // AI-assisted logging: natural language, voice or photo
        binding.btnSmartLog.setOnClickListener { showSmartLogDialog() }

        // Observe data
        vm.profile.observe(viewLifecycleOwner) { profile ->
            if (profile != null) {
                val base = vm.effectiveGoal(profile)
                vm.refreshStreak(base)
                vm.refreshWeatherGoal(base)   // online: adjust for heat; offline: no-op
                renderProgress()
            }
        }

        vm.todayGoal.observe(viewLifecycleOwner) { renderProgress() }
        vm.todayTotalMl.observe(viewLifecycleOwner) { renderProgress() }

        vm.weatherChip.observe(viewLifecycleOwner) { chip ->
            binding.tvWeather.text = chip ?: ""
            binding.tvWeather.visibility = if (chip.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        vm.streak.observe(viewLifecycleOwner) { streak ->
            binding.tvStreak.text = "🔥 $streak"
        }

        // Over-hydration state. Only re-renders the status line — it must not
        // call renderProgress(), which is what refreshes this value.
        vm.safety.observe(viewLifecycleOwner) { applyStatus() }

        // Log list
        val adapter = LogAdapter { log -> vm.deleteLog(log) }
        binding.rvLogs.adapter = adapter
        vm.todayLogs.observe(viewLifecycleOwner) { logs ->
            adapter.submitList(logs)
            binding.tvEmpty.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private var goalReached = false

    /**
     * True only between a user logging a drink and the next render. Without
     * it, every app launch after the goal is met looks like "the goal was
     * just reached" (goalReached starts false) and replays the confetti.
     */
    private var userJustLogged = false

    /** Every user-initiated log goes through here so the celebration is earned. */
    private fun logByUser(ml: Int, note: String? = null, type: DrinkType = DrinkType.WATER) {
        userJustLogged = true
        vm.logIntake(ml, note, type)
    }

    /** Renders the ring/percent/status from the latest total and (weather-adjusted) goal. */
    private fun renderProgress() {
        val total = vm.todayTotalMl.value ?: 0
        val goal = vm.todayGoal.value
            ?: vm.profile.value?.let { vm.effectiveGoal(it) }
            ?: 2500
        val percent = vm.goalPercent(total, goal)
        binding.tvTotal.text = "$total ml"
        binding.progressRing.progress = percent
        binding.tvPercent.text = "$percent%"

        // Goal label always shows the goal actually in effect (weather-adjusted).
        val smart = vm.profile.value?.let { vm.isSmartGoal(it) } == true
        binding.tvGoal.text =
            if (smart) "Daily goal: $goal ml ✨ smart" else "Daily goal: $goal ml"

        val wasReached = goalReached
        goalReached = total >= goal
        lastTotal = total
        lastGoal = goal
        lastPercent = percent

        vm.refreshSafety(goal)   // async; the observer re-runs applyStatus()
        applyStatus()

        if (goalReached && !wasReached && total > 0 && userJustLogged) {
            userJustLogged = false
            celebrate()
        }
    }

    // Last rendered figures, so the safety observer can redraw the status line
    // without re-entering renderProgress().
    private var lastTotal = 0
    private var lastGoal = 2500
    private var lastPercent = 0

    /**
     * Writes the status line. An over-hydration warning takes priority over the
     * encouragement copy — telling someone to "keep sipping" at 6 litres would
     * be actively wrong.
     */
    private fun applyStatus() {
        val warning = vm.safety.value
        if (warning != null && warning.isWarning && warning.message != null) {
            binding.tvStatus.text = warning.message
            binding.tvStatus.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (warning.level == HydrationSafety.Level.HIGH) R.color.error_red
                    else R.color.accent_amber
                )
            )
            return
        }

        binding.tvStatus.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.text_primary)
        )
        binding.tvStatus.text = when {
            lastTotal >= lastGoal -> "🎉 Goal smashed! Amazing!"
            lastPercent >= 75 -> "Almost there — ${lastGoal - lastTotal} ml to go 💪"
            lastPercent >= 40 -> "Nice pace! ${lastGoal - lastTotal} ml left"
            lastTotal > 0 -> "${lastGoal - lastTotal} ml to go — keep sipping 💧"
            else -> "Let's fill it up! 💧"
        }
    }

    /** Confetti + a pop on the drop when the goal is first reached. */
    private fun celebrate() {
        binding.progressRing.animate()
            .scaleX(1.08f).scaleY(1.08f).setDuration(180)
            .withEndAction {
                binding.progressRing.animate().scaleX(1f).scaleY(1f).setDuration(220).start()
            }.start()

        val party = Party(
            speed = 12f,
            maxSpeed = 34f,
            damping = 0.9f,
            spread = 360,
            colors = listOf(0x00B8D4, 0x4DD0E1, 0xFFB300, 0xFF7043, 0xFFFFFF),
            emitter = Emitter(duration = 350, TimeUnit.MILLISECONDS).max(180),
            position = Position.Relative(0.5, 0.28),
        )
        binding.konfetti.start(party)
    }

    private fun showCustomDialog() {
        val d = DialogLogDrinkBinding.inflate(layoutInflater)

        // One chip per drink type; water pre-selected.
        DrinkType.entries.forEach { type ->
            val chip = com.google.android.material.chip.Chip(requireContext()).apply {
                text = "${type.emoji} ${type.label}"
                isCheckable = true
                isChecked = type == DrinkType.WATER
                tag = type
            }
            d.chipsDrink.addView(chip)
        }

        fun selectedType(): DrinkType =
            d.chipsDrink.findViewById<com.google.android.material.chip.Chip>(
                d.chipsDrink.checkedChipId
            )?.tag as? DrinkType ?: DrinkType.WATER

        fun updateHint() {
            val t = selectedType()
            d.tvFactorHint.text =
                if (t.factor >= 1.0) "Counts fully toward your goal"
                else "${t.label} hydrates ~${(t.factor * 100).toInt()}% — e.g. 200 ml → ${t.hydration(200)} ml"
        }
        d.chipsDrink.setOnCheckedStateChangeListener { _, _ -> updateHint() }
        updateHint()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Log a drink")
            .setView(d.root)
            .setPositiveButton("Log") { _, _ ->
                val ml = d.etAmount.text.toString().toIntOrNull()
                val note = d.etNote.text.toString().trim().ifEmpty { null }
                if (ml != null && ml in 1..3000) logByUser(ml, note, selectedType())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── AI-assisted logging ──────────────────────────────────────────────────

    private var smartBinding: DialogSmartLogBinding? = null
    private var pendingParse: List<DrinkTextParser.ParsedDrink> = emptyList()
    private var pendingVision: DrinkImageClassifier.Recognition? = null

    private val voiceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            smartBinding?.etSmart?.setText(spoken)
            refreshSmartPreview(spoken)
        }
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) beginInAppListening()
        else Toast.makeText(
            requireContext(),
            "Microphone permission is needed for voice logging — you can still type or snap.",
            Toast.LENGTH_LONG,
        ).show()
    }

    private var speechRecognizer: android.speech.SpeechRecognizer? = null
    private var isListening = false

    private val photoLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap -> if (bitmap != null) classifyPhoto(bitmap) }

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val bmp = runCatching {
                requireContext().contentResolver.openInputStream(uri).use {
                    android.graphics.BitmapFactory.decodeStream(it)
                }
            }.getOrNull()
            if (bmp != null) classifyPhoto(bmp)
        }
    }

    private fun showSmartLogDialog() {
        val d = DialogSmartLogBinding.inflate(layoutInflater)
        smartBinding = d
        pendingParse = emptyList()
        pendingVision = null

        d.etSmart.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                pendingVision = null
                refreshSmartPreview(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        d.btnVoice.setOnClickListener { startVoiceInput() }
        d.btnPhoto.setOnClickListener { choosePhotoSource() }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("✨ Smart log")
            .setView(d.root)
            .setPositiveButton("Log") { _, _ -> commitSmartLog() }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener {
                stopInAppListening()
                smartBinding = null
            }
            .show()
    }

    /** Runs the on-device NLP parser and previews what would be logged. */
    private fun refreshSmartPreview(text: String) {
        val d = smartBinding ?: return
        if (text.isBlank()) {
            d.cardPreview.visibility = View.GONE
            pendingParse = emptyList()
            return
        }
        val result = DrinkTextParser.parse(text)
        pendingParse = result.drinks

        if (result.isEmpty) {
            d.cardPreview.visibility = View.VISIBLE
            d.tvPreviewTitle.text = "Couldn't read that yet"
            d.tvPreviewBody.text = "Try something like \"a glass of water\" or \"250 ml coffee\"."
            d.tvPreviewMeta.text = ""
            return
        }

        d.cardPreview.visibility = View.VISIBLE
        d.tvPreviewTitle.text = "Understood ${result.drinks.size} drink${if (result.drinks.size == 1) "" else "s"}"
        d.tvPreviewBody.text = result.drinks.joinToString("\n") {
            val credited = it.type.hydration(it.amountMl)
            val creditNote = if (credited != it.amountMl) " → $credited ml counted" else ""
            "${it.type.emoji} ${it.type.label} · ${it.amountMl} ml$creditNote"
        }
        val avgConf = (result.drinks.sumOf { it.confidence } / result.drinks.size * 100).toInt()
        val note = result.drinks.firstOrNull { it.note != null }?.note
        d.tvPreviewMeta.text = buildString {
            append("Confidence $avgConf%")
            if (note != null) append(" · note: \"$note\"")
        }
    }

    /**
     * Voice input, done properly: we listen *inside* the dialog with the
     * SpeechRecognizer API — live partial transcription, generous silence
     * timeouts, tap-again-to-stop. The old hand-off to the system popup
     * dismissed itself after ~1 s of perceived silence.
     */
    private fun startVoiceInput() {
        if (isListening) {
            stopInAppListening()
            return
        }
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(requireContext())) {
            // Rare: no on-device recognition service — fall back to the system UI.
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "What did you drink?")
            }
            runCatching { voiceLauncher.launch(intent) }.onFailure {
                Toast.makeText(requireContext(), "No speech recogniser available on this device.", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            requireContext(), android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) beginInAppListening()
        else micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    private fun beginInAppListening() {
        val d = smartBinding ?: return
        val recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(requireContext())
        speechRecognizer = recognizer
        isListening = true

        d.btnVoice.text = "🔴  Stop"
        d.cardPreview.visibility = View.VISIBLE
        d.tvPreviewTitle.text = "Listening"
        d.tvPreviewBody.text = "Go ahead — e.g. \"two glasses of water and a coffee\""
        d.tvPreviewMeta.text = "I'll wait while you think. Tap the button again to stop."

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Generous pauses: don't give up just because the user breathed.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 6000L)
        }

        recognizer.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {
                smartBinding?.tvPreviewTitle?.text = "Listening — got you…"
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                smartBinding?.tvPreviewTitle?.text = "Working it out…"
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) smartBinding?.etSmart?.setText(text)
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                finishListeningUi()
                if (!text.isNullOrBlank()) {
                    smartBinding?.etSmart?.setText(text)
                    refreshSmartPreview(text)
                }
            }

            override fun onError(error: Int) {
                finishListeningUi()
                val b = smartBinding ?: return
                when (error) {
                    android.speech.SpeechRecognizer.ERROR_NO_MATCH,
                    android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        b.cardPreview.visibility = View.VISIBLE
                        b.tvPreviewTitle.text = "Didn't catch that"
                        b.tvPreviewBody.text = "Tap 🎤 and try again a little closer to the phone."
                        b.tvPreviewMeta.text = ""
                    }
                    android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        Toast.makeText(requireContext(), "Microphone permission is needed for voice logging.", Toast.LENGTH_SHORT).show()
                    else -> {
                        b.tvPreviewTitle.text = "Voice hiccup (code $error)"
                        b.tvPreviewBody.text = "Try again, or just type it."
                        b.tvPreviewMeta.text = ""
                    }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer.startListening(intent)
    }

    private fun stopInAppListening() {
        speechRecognizer?.stopListening()
        finishListeningUi()
    }

    private fun finishListeningUi() {
        isListening = false
        smartBinding?.btnVoice?.text = "🎤  Speak"
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun choosePhotoSource() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Recognise a drink")
            .setItems(arrayOf("Take a photo", "Pick from gallery")) { _, which ->
                if (which == 0) runCatching { photoLauncher.launch(null) }
                    .onFailure { Toast.makeText(requireContext(), "No camera available.", Toast.LENGTH_SHORT).show() }
                else galleryLauncher.launch("image/*")
            }
            .show()
    }

    /** Runs the bundled on-device vision model over the captured image. */
    private fun classifyPhoto(bitmap: android.graphics.Bitmap) {
        val d = smartBinding ?: return
        d.cardPreview.visibility = View.VISIBLE
        d.tvPreviewTitle.text = "Looking at your photo…"
        d.tvPreviewBody.text = ""
        d.tvPreviewMeta.text = ""

        viewLifecycleOwner.lifecycleScope.launch {
            val classifier = DrinkImageClassifier()
            val outcome = try { classifier.classify(bitmap) } finally { classifier.close() }
            val b = smartBinding ?: return@launch

            if (outcome is DrinkImageClassifier.Outcome.NotFound) {
                b.tvPreviewTitle.text = "No drink recognised"
                b.tvPreviewBody.text =
                    "Try a shot that shows the whole cup or bottle — close-ups without context confuse the model."
                // Explainability: show what the model actually saw.
                b.tvPreviewMeta.text = if (outcome.labels.isEmpty()) ""
                else "The model saw: ${outcome.labels.take(4).joinToString(", ")}"
                pendingVision = null
                return@launch
            }
            val rec = (outcome as DrinkImageClassifier.Outcome.Found).recognition

            pendingVision = rec
            pendingParse = emptyList()
            val credited = rec.type.hydration(rec.estimatedMl)
            val creditNote = if (credited != rec.estimatedMl) " → $credited ml counted" else ""
            b.tvPreviewTitle.text = "Recognised on-device"
            b.tvPreviewBody.text =
                "${rec.type.emoji} ${rec.type.label} · ~${rec.estimatedMl} ml$creditNote"
            b.tvPreviewMeta.text = buildString {
                append("Confidence ${(rec.confidence * 100).toInt()}%")
                rec.containerLabel?.let { append(" · saw a $it") }
                if (rec.labels.isNotEmpty()) append(" · ${rec.labels.take(3).joinToString(", ")}")
                append(" · edit the amount after logging if it's off")
            }
        }
    }

    private fun commitSmartLog() {
        val vision = pendingVision
        if (vision != null) {
            logByUser(vision.estimatedMl, "Photo log", vision.type)
            Toast.makeText(requireContext(), "Logged ${vision.estimatedMl} ml ${vision.type.label}", Toast.LENGTH_SHORT).show()
            return
        }
        if (pendingParse.isEmpty()) {
            Toast.makeText(requireContext(), "Nothing recognised to log.", Toast.LENGTH_SHORT).show()
            return
        }
        pendingParse.forEach { logByUser(it.amountMl, it.note, it.type) }
        val total = pendingParse.sumOf { it.type.hydration(it.amountMl) }
        Toast.makeText(
            requireContext(),
            "Logged ${pendingParse.size} drink${if (pendingParse.size == 1) "" else "s"} · $total ml counted",
            Toast.LENGTH_SHORT,
        ).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        smartBinding = null
        _binding = null
    }
}
