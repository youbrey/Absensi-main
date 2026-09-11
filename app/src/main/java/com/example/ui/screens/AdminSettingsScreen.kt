package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.GoogleSheetsManager
import com.example.ui.components.ScheduleMode
import com.example.ui.viewmodel.AttendanceViewModel
import com.example.util.NotificationHelper

@Composable
fun AdminSettingsScreen(
    viewModel: AttendanceViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var tokenInput by remember { mutableStateOf(viewModel.syncTokenState.value) }
    var webhookInput by remember { mutableStateOf(viewModel.webhookUrlState.value) }
    val pushEnabled by viewModel.pushNotificationsEnabled.collectAsState()
    val notificationPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.setPushNotifications(granted) }
    val currentScheduleMode by viewModel.scheduleMode.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Header
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = null,
                        tint = Color(0xFFF59E0B),
                        modifier = Modifier.size(32.dp)
                    )

                    Column {
                        Text(
                            text = "PENGATURAN SISTEM & GOOGLE SHEETS",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Konfigurasi Webhook API, Push Notification & Keamanan",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }
                }

                IconButton(
                    onClick = {
                        viewModel.logoutAdmin()
                        Toast.makeText(context, "Berhasil Keluar Mode Admin", Toast.LENGTH_SHORT).show()
                    },
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFF1E293B))
                ) {
                    Icon(
                        Icons.Default.Logout,
                        contentDescription = "Keluar Admin",
                        tint = Color(0xFFF43F5E)
                    )
                }
            }
        }

        // Schedule & Access Control Settings Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = when (currentScheduleMode) {
                            ScheduleMode.FORCE_OPEN -> Icons.Default.LockOpen
                            ScheduleMode.FORCE_LOCKED -> Icons.Default.Lock
                            else -> Icons.Default.AccessTime
                        },
                        contentDescription = null,
                        tint = when (currentScheduleMode) {
                            ScheduleMode.FORCE_OPEN -> Color(0xFF10B981)
                            ScheduleMode.FORCE_LOCKED -> Color(0xFFEF4444)
                            else -> Color(0xFF38BDF8)
                        }
                    )
                    Text(
                        text = "KONTROL JADWAL ABSENSI & UJI COBA",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A)
                    )
                }

                Text(
                    text = "Atur apakah jadwal absensi mengikuti operasional otomatis, dibuka secara paksa (untuk uji coba / simulasi), atau dikunci total:",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Option 1: Automatic
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (currentScheduleMode == ScheduleMode.AUTOMATIC) Color(0xFFE2E8F0) else Color(0xFFF8FAFC))
                            .clickable {
                                viewModel.setScheduleMode(ScheduleMode.AUTOMATIC)
                                Toast.makeText(context, "Mode Jadwal Otomatis Diaktifkan", Toast.LENGTH_SHORT).show()
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        RadioButton(
                            selected = (currentScheduleMode == ScheduleMode.AUTOMATIC),
                            onClick = {
                                viewModel.setScheduleMode(ScheduleMode.AUTOMATIC)
                                Toast.makeText(context, "Mode Jadwal Otomatis Diaktifkan", Toast.LENGTH_SHORT).show()
                            }
                        )
                        Column {
                            Text("Otomatis (Jam Operasional)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("Masuk: 07.00-09.00 WITA • Pulang: 12.00-14.00 WITA", fontSize = 11.sp, color = Color.Gray)
                        }
                    }

                    // Option 2: Force Open (Trial / Override)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (currentScheduleMode == ScheduleMode.FORCE_OPEN) Color(0xFFD1FAE5) else Color(0xFFF8FAFC))
                            .clickable {
                                viewModel.setScheduleMode(ScheduleMode.FORCE_OPEN)
                                Toast.makeText(context, "Jadwal Absensi Berhasil DIBUKA (Mode Uji Coba)", Toast.LENGTH_SHORT).show()
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        RadioButton(
                            selected = (currentScheduleMode == ScheduleMode.FORCE_OPEN),
                            onClick = {
                                viewModel.setScheduleMode(ScheduleMode.FORCE_OPEN)
                                Toast.makeText(context, "Jadwal Absensi Berhasil DIBUKA (Mode Uji Coba)", Toast.LENGTH_SHORT).show()
                            },
                            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF059669))
                        )
                        Column {
                            Text("Buka Jadwal (Uji Coba / Dispensasi)", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF047857))
                            Text("Pegawai / Admin bisa absensi kapan saja tanpa terikat jam operasional.", fontSize = 11.sp, color = Color(0xFF065F46))
                        }
                    }

                    // Option 3: Force Locked
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (currentScheduleMode == ScheduleMode.FORCE_LOCKED) Color(0xFFFEE2E2) else Color(0xFFF8FAFC))
                            .clickable {
                                viewModel.setScheduleMode(ScheduleMode.FORCE_LOCKED)
                                Toast.makeText(context, "Jadwal Absensi Berhasil DIKUNCI", Toast.LENGTH_SHORT).show()
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        RadioButton(
                            selected = (currentScheduleMode == ScheduleMode.FORCE_LOCKED),
                            onClick = {
                                viewModel.setScheduleMode(ScheduleMode.FORCE_LOCKED)
                                Toast.makeText(context, "Jadwal Absensi Berhasil DIKUNCI", Toast.LENGTH_SHORT).show()
                            },
                            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFDC2626))
                        )
                        Column {
                            Text("Kunci Jadwal (Ditutup Total)", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFFB91C1C))
                            Text("Mencegah pengiriman absensi meskipun dalam jam operasional.", fontSize = 11.sp, color = Color(0xFF991B1B))
                        }
                    }
                }
            }
        }

        // Google Sheets Integration Settings
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.TableRows, contentDescription = null, tint = Color(0xFF059669))
                    Text(
                        text = "SINKRONISASI GOOGLE SHEETS API",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF059669)
                    )
                }

                Text(
                    text = "URL Webhook Google Apps Script tempat data absensi WFH dikirim secara otomatis:",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val usingBuiltIn by viewModel.isUsingBuiltInSyncConfig.collectAsState()
                if (usingBuiltIn) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFECFDF5))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF059669), modifier = Modifier.size(16.dp))
                        Text(
                            "Memakai konfigurasi bawaan dari build ini. Isi kolom di bawah hanya jika perlu override manual.",
                            fontSize = 11.sp,
                            color = Color(0xFF065F46)
                        )
                    }
                }

                OutlinedTextField(
                    value = webhookInput,
                    onValueChange = {
                        webhookInput = it
                    },
                    label = { Text("URL Webhook Apps Script") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                OutlinedTextField(value = tokenInput, onValueChange = { tokenInput = it },
                    label = { Text("Token sinkronisasi") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth())

                Button(
                    onClick = {
                        val saved = viewModel.saveWebhook(webhookInput, tokenInput)
                        Toast.makeText(context, if (saved) "Konfigurasi webhook disimpan" else "Isi token dan URL deployment HTTPS script.google.com/macros/s/.../exec", Toast.LENGTH_LONG).show()
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Simpan Konfigurasi Webhook")
                }
            }
        }

        // Push Notification System Settings
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            text = "NOTIFIKASI PENGINGAT HARIAN",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Switch(
                        checked = pushEnabled,
                        onCheckedChange = {
                            if (it && android.os.Build.VERSION.SDK_INT >= 33) {
                                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            } else { viewModel.setPushNotifications(it) }
                        }
                    )
                }

                Text(
                    text = "Jadwal Pengingat Absensi Harian WFH:\n• Pengingat Absensi Masuk: Pukul 07.15 WITA\n• Pengingat Absensi Pulang: Pukul 12.15 WITA",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            NotificationHelper.showAbsensiReminder(context, isMasuk = true)
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Tes Notif Masuk", fontSize = 11.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            NotificationHelper.showAbsensiReminder(context, isMasuk = false)
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Tes Notif Pulang", fontSize = 11.sp)
                    }
                }
            }
        }

        // Encryption Security Info Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.EnhancedEncryption, contentDescription = null, tint = Color(0xFF10B981))
                    Text(
                        text = "PENYIMPANAN & TRANSMISI DATA",
                        color = Color(0xFF10B981),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "Data absensi tersimpan di database privat aplikasi (tanpa enkripsi database tambahan). Pengiriman memakai HTTPS. Kata sandi disimpan sebagai hash dengan salt. SHA-256 digunakan sebagai ID rekaman untuk mencegah duplikasi kiriman.",
                    color = Color(0xFFCBD5E1),
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }
    }
}
