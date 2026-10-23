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
            // Follow the schedule mode shared by the admin (cached from the server by the app):
            // locked -> never remind, forced open -> remind, otherwise only inside the real window.
            // Inexact alarms may be delayed by Android; do not announce a closed window.
            val mode = context.getSharedPreferences("admin_settings", Context.MODE_PRIVATE)
                .getString("schedule_mode", "AUTOMATIC")
            val shouldRemind = when (mode) {
                "FORCE_LOCKED" -> false
                "FORCE_OPEN" -> true
                else -> AttendancePolicy.window() == if (masuk) "MASUK" else "PULANG"
            }
            if (shouldRemind) {
                NotificationHelper.showAbsensiReminder(context, masuk)
            }
        }
        NotificationHelper.scheduleReminders(context, enabled)
    }
}
