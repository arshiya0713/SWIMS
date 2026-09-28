package com.swims.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.swims.app.R
import com.swims.app.data.SettingsStore
import com.swims.app.data.db.SwimsDatabase
import com.swims.app.data.repository.SwimsRepository
import com.swims.app.ml.WeatherAdjuster
import com.swims.app.ui.onboarding.OnboardingActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Home-screen widget: live progress + one-tap "+250 ml" that logs water
 * without opening the app. Refreshed after every in-app log and every
 * 30 min by the system (so the date rollover is picked up).
 */
class SwimsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        refresh(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_QUICK_ADD) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val repo = SwimsRepository(SwimsDatabase.getInstance(context).dao())
                    repo.logIntake(QUICK_ADD_ML)
                    updateAllBlocking(context)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    companion object {
        const val ACTION_QUICK_ADD = "com.swims.app.widget.QUICK_ADD"
        private const val QUICK_ADD_ML = 250

        /** Fire-and-forget refresh, safe from any thread/context. */
        fun refresh(context: Context) {
            CoroutineScope(Dispatchers.IO).launch { updateAllBlocking(context) }
        }

        /** Reads today's state and pushes RemoteViews to every widget instance. */
        private suspend fun updateAllBlocking(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, SwimsWidgetProvider::class.java))
            if (ids.isEmpty()) return

            val repo = SwimsRepository(SwimsDatabase.getInstance(context).dao())
            val profile = repo.getProfile()
            val total = repo.todayTotal()

            // Goal = adaptive/formula goal + cached weather bonus (no network here)
            var goal = profile?.let { repo.effectiveGoal(it) } ?: 2500
            val store = SettingsStore.get(context)
            if (store.weatherEnabled && store.hasLocation() && !store.lastMaxTempC.isNaN()) {
                val ageMs = System.currentTimeMillis() - store.lastWeatherAtMs
                if (ageMs in 0 until 12 * 3_600_000L) {
                    goal = (goal + WeatherAdjuster.bonusMl(store.lastMaxTempC.toDouble()))
                        .coerceIn(1500, 5000)
                }
            }

            val percent = if (goal <= 0) 0 else ((total * 100f) / goal).toInt().coerceIn(0, 100)

            val views = RemoteViews(context.packageName, R.layout.widget_swims).apply {
                setTextViewText(R.id.tv_w_percent, "💧 $percent%")
                setTextViewText(R.id.tv_w_amount, "$total / $goal ml")
                setProgressBar(R.id.pb_w, 100, percent, false)
                setTextViewText(
                    R.id.tv_w_hint,
                    if (total >= goal) "Goal reached! 🎉" else "SWIMS · tap to open"
                )

                // Tap anywhere → open the app
                val openIntent = Intent(context, OnboardingActivity::class.java)
                setOnClickPendingIntent(
                    R.id.widget_root,
                    PendingIntent.getActivity(
                        context, 0, openIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )

                // +250 ml button → broadcast back to this provider
                val addIntent = Intent(context, SwimsWidgetProvider::class.java)
                    .setAction(ACTION_QUICK_ADD)
                setOnClickPendingIntent(
                    R.id.btn_w_add,
                    PendingIntent.getBroadcast(
                        context, 1, addIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }

            ids.forEach { mgr.updateAppWidget(it, views) }
        }
    }
}
