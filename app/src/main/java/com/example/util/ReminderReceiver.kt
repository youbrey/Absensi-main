package com.example.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.domain.AttendancePolicy

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val enabled = context.getSharedPreferences("admin_settings", Context.MODE_PRIVATE)
            .getBoolean("push_enabled", true)
        if (enabled && intent.action == NotificationHelper.ACTION_REMINDER) {
            val masuk = intent.getBooleanExtra("masuk", true)
            // Inexact alarms may be delayed by Android; do not announce a closed window.
            if (AttendancePolicy.window() == if (masuk) "MASUK" else "PULANG") {
                NotificationHelper.showAbsensiReminder(context, masuk)
            }
        }
        NotificationHelper.scheduleReminders(context, enabled)
    }
}
