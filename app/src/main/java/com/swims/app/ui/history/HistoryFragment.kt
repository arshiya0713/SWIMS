package com.swims.app.ui.history

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.swims.app.R
import com.swims.app.databinding.DialogAskSwimsBinding
import com.swims.app.databinding.FragmentHistoryBinding
import com.swims.app.viewmodel.HistoryViewModel

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!
    private val vm: HistoryViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        vm.insight.observe(viewLifecycleOwner) { binding.tvInsight.text = it }
        binding.btnAsk.setOnClickListener { showAskDialog() }
        setupAchievements()
        setupHeatmap()
        setupWeeklyChart()
        setupMonthlyChart()
    }

    private fun setupAchievements() {
        val adapter = AchievementAdapter()
        binding.rvAchievements.layoutManager = GridLayoutManager(requireContext(), 4)
        binding.rvAchievements.adapter = adapter
        vm.achievements.observe(viewLifecycleOwner) { list ->
            adapter.submitList(list)
            val n = list.count { it.unlocked }
            binding.tvAchievementsTitle.text = "🏆 Achievements · $n/${list.size}"
        }
        vm.newUnlocks.observe(viewLifecycleOwner) { fresh ->
            fresh.forEach {
                Toast.makeText(requireContext(), "🏆 Achievement unlocked: ${it.emoji} ${it.title}!", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupHeatmap() {
        binding.btnMonthPrev.setOnClickListener { vm.prevMonth() }
        binding.btnMonthNext.setOnClickListener { vm.nextMonth() }
        vm.heatmap.observe(viewLifecycleOwner) { (month, ratios) ->
            binding.heatmap.setMonth(month, ratios)
            binding.tvMonthLabel.text =
                month.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()) +
                    " " + month.year
        }
    }

    private fun setupWeeklyChart() {
        vm.weekly.observe(viewLifecycleOwner) { stats ->
            if (stats.isEmpty()) return@observe

            val entries = stats.mapIndexed { i, s -> BarEntry(i.toFloat(), s.totalMl.toFloat()) }
            val labels = stats.map { it.date.substring(5) } // MM-DD

            val dataSet = BarDataSet(entries, "ml consumed").apply {
                color = Color.parseColor("#00E5FF")
                valueTextColor = Color.parseColor("#EDEBFF")
                valueTextSize = 10f
            }

            binding.chartWeekly.apply {
                data = BarData(dataSet)
                xAxis.apply {
                    valueFormatter = IndexAxisValueFormatter(labels)
                    position = XAxis.XAxisPosition.BOTTOM
                    granularity = 1f
                    setDrawGridLines(false)
                    textColor = Color.parseColor("#A29BD4")
                }
                axisLeft.textColor = Color.parseColor("#A29BD4")
                axisLeft.gridColor = Color.parseColor("#2A2166")
                legend.textColor = Color.parseColor("#A29BD4")
                axisRight.isEnabled = false
                description.isEnabled = false
                animateY(600)
                invalidate()
            }
        }
    }

    private fun setupMonthlyChart() {
        vm.monthly.observe(viewLifecycleOwner) { stats ->
            if (stats.isEmpty()) return@observe

            val entries = stats.mapIndexed { i, s -> Entry(i.toFloat(), s.totalMl.toFloat()) }
            val labels = stats.map { it.date.substring(5) }

            val dataSet = LineDataSet(entries, "Daily ml (30 days)").apply {
                color = Color.parseColor("#8B5CF6")
                setCircleColor(Color.parseColor("#00E5FF"))
                circleRadius = 4f
                valueTextColor = Color.parseColor("#EDEBFF")
                valueTextSize = 9f
                lineWidth = 3f
                setDrawFilled(true)
                fillColor = Color.parseColor("#5B21B6")
                fillAlpha = 90
            }

            binding.chartMonthly.apply {
                data = LineData(dataSet)
                xAxis.apply {
                    valueFormatter = IndexAxisValueFormatter(labels)
                    position = XAxis.XAxisPosition.BOTTOM
                    labelRotationAngle = -45f
                    setDrawGridLines(false)
                    granularity = 1f
                    textColor = Color.parseColor("#A29BD4")
                }
                axisLeft.textColor = Color.parseColor("#A29BD4")
                axisLeft.gridColor = Color.parseColor("#2A2166")
                legend.textColor = Color.parseColor("#A29BD4")
                axisRight.isEnabled = false
                description.isEnabled = false
                animateY(600)
                invalidate()
            }
        }
    }

    // ── Ask SWIMS — on-device Q&A over the user's own history ────────────────

    private fun showAskDialog() {
        val d = DialogAskSwimsBinding.inflate(layoutInflater)

        fun addBubble(text: String, fromUser: Boolean) {
            val tv = android.widget.TextView(requireContext()).apply {
                this.text = text
                textSize = 14f
                setLineSpacing(0f, 1.15f)
                setTextColor(
                    if (fromUser) android.graphics.Color.parseColor("#081C33")
                    else android.graphics.Color.parseColor("#EDEBFF")
                )
                background = androidx.core.content.ContextCompat.getDrawable(
                    requireContext(),
                    if (fromUser) R.drawable.bg_bubble_user else R.drawable.bg_bubble_bot
                )
                setPadding(dp(14), dp(10), dp(14), dp(10))
            }
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(6)
                gravity = if (fromUser) android.view.Gravity.END else android.view.Gravity.START
                marginStart = if (fromUser) dp(48) else 0
                marginEnd = if (fromUser) 0 else dp(48)
            }
            d.llChat.addView(tv, lp)
            d.scrollChat.post { d.scrollChat.fullScroll(View.FOCUS_DOWN) }
        }

        fun send() {
            val q = d.etQuestion.text.toString().trim()
            if (q.isEmpty()) return
            d.etQuestion.setText("")
            addBubble(q, fromUser = true)
            vm.ask(q) { answer -> if (isAdded) addBubble(answer, fromUser = false) }
        }

        d.btnSend.setOnClickListener { send() }
        d.etQuestion.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) { send(); true } else false
        }

        // Greeting with capabilities, so the empty state teaches by example.
        addBubble(
            "Hi! I answer from your own data, right here on your phone. " +
                "Try: \"How much today?\" · \"What's my streak?\" · \"When do I usually drink?\"",
            fromUser = false,
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("💬 Ask SWIMS")
            .setView(d.root)
            .setNegativeButton("Close", null)
            .show()
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
