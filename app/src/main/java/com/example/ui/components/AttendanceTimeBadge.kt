package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.AttendancePolicy
import kotlinx.coroutines.delay

enum class ScheduleMode(val displayName: String) {
    AUTOMATIC("Otomatis (Jam Operasional)"),
    FORCE_OPEN("Buka Jadwal (Uji Coba / Dispensasi)"),
    FORCE_LOCKED("Kunci Jadwal (Ditutup Total)")
}

data class TimeWindowStatus(
    val isOpen: Boolean,
    val windowType: String, // "MASUK", "PULANG", "UJI_COBA", or "CLOSED"
    val title: String,
    val subtitle: String,
    val color: Color
)

fun checkTimeWindow(): TimeWindowStatus {
    val window = AttendancePolicy.window()

    return when {
        window == "MASUK" -> {
            TimeWindowStatus(
                isOpen = true,
                windowType = "MASUK",
                title = "JADWAL ABSENSI MASUK WFH DIBUKA (07.00 - 09.00 WITA)",
                subtitle = "Sistem menerima verifikasi kehadiran masuk.",
                color = Color(0xFF10B981) // Green
            )
        }
        window == "PULANG" -> {
            TimeWindowStatus(
                isOpen = true,
                windowType = "PULANG",
                title = "JADWAL ABSENSI PULANG WFH DIBUKA (12.00 - 14.00 WITA)",
                subtitle = "Sistem menerima verifikasi kepulangan.",
                color = Color(0xFF38BDF8) // Blue
            )
        }
        else -> {
            TimeWindowStatus(
                isOpen = false,
                windowType = "CLOSED",
                title = "DILUAR JAM OPERASIONAL ABSENSI WFH",
                subtitle = "Masuk: 07.00-09.00 WITA | Pulang: 12.00-14.00 WITA",
                color = Color(0xFFEF4444) // Red
            )
        }
    }
}

fun getEffectiveTimeWindowStatus(mode: ScheduleMode): TimeWindowStatus {
    val base = checkTimeWindow()
    return when (mode) {
        ScheduleMode.AUTOMATIC -> base
        ScheduleMode.FORCE_OPEN -> TimeWindowStatus(
            isOpen = true,
            windowType = if (base.windowType != "CLOSED") base.windowType else "UJI_COBA",
            title = "JADWAL ABSENSI DIBUKA ADMIN (MODE UJI COBA)",
            subtitle = "Admin membuka akses absensi secara manual untuk simulasi / uji coba.",
            color = Color(0xFF10B981)
        )
        ScheduleMode.FORCE_LOCKED -> TimeWindowStatus(
            isOpen = false,
            windowType = "CLOSED",
            title = "JADWAL ABSENSI DIKUNCI ADMIN",
            subtitle = "Sistem absensi sedang dikunci oleh Admin secara manual.",
            color = Color(0xFFEF4444)
        )
    }
}

@Composable
fun AttendanceTimeBadge(
    scheduleMode: ScheduleMode = ScheduleMode.AUTOMATIC,
    modifier: Modifier = Modifier
) {
    var status by remember(scheduleMode) { mutableStateOf(getEffectiveTimeWindowStatus(scheduleMode)) }
    LaunchedEffect(scheduleMode) {
        while (true) {
            status = getEffectiveTimeWindowStatus(scheduleMode)
            delay(1000)
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = status.color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(14.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(status.color))
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = when {
                    scheduleMode == ScheduleMode.FORCE_OPEN -> Icons.Default.LockOpen
                    scheduleMode == ScheduleMode.FORCE_LOCKED -> Icons.Default.Lock
                    status.isOpen -> Icons.Default.CheckCircle
                    else -> Icons.Default.LockClock
                },
                contentDescription = null,
                tint = status.color,
                modifier = Modifier.size(28.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = status.title,
                    color = status.color,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = status.subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }
        }
    }
}
