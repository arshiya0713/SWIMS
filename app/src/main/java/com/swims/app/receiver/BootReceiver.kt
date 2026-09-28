package com.swims.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.swims.app.data.db.SwimsDatabase
import com.swims.app.util.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Reschedules reminders after device reboot */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // goAsync() keeps the process alive until finish() — otherwise the OS may
        // kill it before the DB read completes and reminders are never rescheduled.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val profile = SwimsDatabase.getInstance(context).dao().getProfile()
                if (profile != null && profile.remindersEnabled) {
                    ReminderScheduler.schedule(context, profile.reminderIntervalHours)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
