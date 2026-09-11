package com.example.domain

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object AttendancePolicy {
    val zone: TimeZone get() = TimeZone.getTimeZone("Asia/Makassar")
    const val MASUK = "ABSENSI MASUK"
    const val PULANG = "ABSENSI PULANG"

    fun format(pattern: String, timestamp: Long = System.currentTimeMillis()): String =
        SimpleDateFormat(pattern, Locale("id", "ID")).apply { timeZone = zone }.format(Date(timestamp))

    fun window(timestamp: Long = System.currentTimeMillis()): String {
        val cal = Calendar.getInstance(zone).apply { timeInMillis = timestamp }
        val seconds = cal.get(Calendar.HOUR_OF_DAY) * 3600 + cal.get(Calendar.MINUTE) * 60 + cal.get(Calendar.SECOND)
        return when (seconds) {
            in 7 * 3600..9 * 3600 -> "MASUK"
            in 12 * 3600..14 * 3600 -> "PULANG"
            else -> "CLOSED"
        }
    }

    fun dayBounds(timestamp: Long): Pair<Long, Long> {
        val cal = Calendar.getInstance(zone).apply {
            timeInMillis = timestamp
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 1)
        return start to cal.timeInMillis
    }

    fun monthLabel(timestamp: Long = System.currentTimeMillis()) = format("MMMM yyyy", timestamp)
}
