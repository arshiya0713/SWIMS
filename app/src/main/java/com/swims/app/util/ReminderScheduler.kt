package com.swims.app.util

import android.content.Context
import androidx.work.*
import com.swims.app.SwimsApplication
import java.util.concurrent.TimeUnit
import android.app.NotificationManager
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.swims.app.R
import com.swims.app.data.db.SwimsDatabase
import com.swims.app.data.repository.SwimsRepository

object ReminderScheduler {

    private const val WORK_TAG = "swims_reminder"

    fun schedule(context: Context, intervalHours: Int) {
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(
            intervalHours.toLong(), TimeUnit.HOURS
        )
            .addTag(WORK_TAG)
            .setConstraints(Constraints.NONE)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_TAG,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG)
    }
}

class ReminderWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        // Check permission on Android 13+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return Result.success()
        }

        val repo = SwimsRepository(SwimsDatabase.getInstance(context).dao())
        val profile = repo.getProfile() ?: return Result.success()

        // Close the learning loop even when reminders are off, so the policy
        // still learns from decisions already made, and share/refresh the
        // federated model opportunistically.
        repo.resolvePendingRewards()
        runCatching { repo.runFederatedRound(context) }

        if (!profile.remindersEnabled) return Result.success()

        // Smart timing: skip the nudge when the model says it isn't needed
        // (on pace, just logged, or an hour the user never drinks in).
        var text = "Log your water intake and stay on track."
        if (profile.smartFeaturesEnabled) {
            val decision = repo.reminderDecision()
            if (!decision.notify) return Result.success()
            decision.message?.let { text = it }
        }

        val notification = NotificationCompat.Builder(context, SwimsApplication.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_water_drop)
            .setContentTitle("Time to hydrate! 💧")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Fixed ID: a new reminder replaces the previous one instead of piling up
        manager.notify(NOTIFICATION_ID, notification)

        return Result.success()
    }

    companion object {
        private const val NOTIFICATION_ID = 1002
    }
}
