package com.example.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.core.content.FileProvider
import com.example.data.AttendanceSummary
import com.example.ui.components.AttendanceTimeBadge
import com.example.ui.viewmodel.AttendanceViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDashboardScreen(
    viewModel: AttendanceViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val allRecords by viewModel.allAttendanceList.collectAsState()
    val selectedMonth by viewModel.selectedMonthFilter.collectAsState()
    val scheduleMode by viewModel.scheduleMode.collectAsState()

    val monthRecords = allRecords.filter { com.example.domain.AttendancePolicy.monthLabel(it.timestamp) == selectedMonth }
    val totalRecords = monthRecords.size
    val syncedCount = monthRecords.count { it.isSyncedToSheets }
    val unsyncedCount = totalRecords - syncedCount

    var searchQuery by remember { mutableStateOf("") }

    val filteredRecords = remember(monthRecords, searchQuery) {
        if (searchQuery.isBlank()) {
            monthRecords
        } else {
            monthRecords.filter {
                it.namaLengkap.contains(searchQuery, ignoreCase = true) ||
                it.nip.contains(searchQuery, ignoreCase = true) ||
                it.jabatan.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Top Header
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFF59E0B)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.AdminPanelSettings,
                                contentDescription = null,
                                tint = Color.White
                            )
                        }
                        Column {
                            Text(
                                text = "DASHBOARD MONITORING ADMIN",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "Sekretariat DPRD Kota Bitung",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconButton(
                            onClick = { viewModel.syncAllUnsyncedRecords() },
                            colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFF1E293B))
                        ) {
                            Icon(
                                Icons.Default.Sync,
                                contentDescription = "Sync Sheets",
                                tint = Color(0xFF38BDF8)
                            )
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

                // Stats Chips Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatCard(
                        title = "TOTAL WFH",
                        value = "$totalRecords Log",
                        color = Color(0xFF38BDF8),
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        title = "SYNC SHEETS",
                        value = "$syncedCount Sync",
                        color = Color(0xFF10B981),
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        title = "PENDING",
                        value = "$unsyncedCount Lokal",
                        color = Color(0xFFF59E0B),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Active Schedule Status Badge
        AttendanceTimeBadge(scheduleMode = scheduleMode)

        var monthMenuOpen by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { monthMenuOpen = true }) { Text("Periode: $selectedMonth") }
            DropdownMenu(expanded = monthMenuOpen, onDismissRequest = { monthMenuOpen = false }) {
                (listOf(com.example.domain.AttendancePolicy.monthLabel()) + allRecords.map {
                    com.example.domain.AttendancePolicy.monthLabel(it.timestamp)
                }).distinct().forEach { month ->
                    DropdownMenuItem(text = { Text(month) }, onClick = {
                        viewModel.selectedMonthFilter.value = month
                        monthMenuOpen = false
                    })
                }
            }
        }

        // Action Buttons Bar: Export PDF & Export Excel/CSV
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {
                    viewModel.exportReportPdf()
                },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
            ) {
                Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Ekspor PDF", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = {
                    viewModel.exportReportCsv()
                },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
            ) {
                Icon(Icons.Default.TableChart, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Ekspor Excel", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            text = "Tombol di atas hanya ekspor data lokal HP ini. Untuk rekap SEMUA pegawai dari SEMUA device, gunakan tombol di bawah:",
            fontSize = 10.sp,
            color = Color(0xFF64748B)
        )

        // Action Buttons Bar: Rekap Gabungan Semua Pegawai (ditarik dari Google Sheets, bukan DB lokal)
        val isExportingRekapGabungan by viewModel.isExportingRekapGabungan.collectAsState()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { viewModel.exportRekapGabunganPdf() },
                enabled = !isExportingRekapGabungan,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))
            ) {
                if (isExportingRekapGabungan) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Rekap Gabungan PDF", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Button(
                onClick = { viewModel.exportRekapGabunganCsv() },
                enabled = !isExportingRekapGabungan,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0EA5E9))
            ) {
                if (isExportingRekapGabungan) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Rekap Gabungan Excel", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Search Field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Cari NAMA, NIP, atau Jabatan...", fontSize = 12.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            singleLine = true
        )

        // Attendance Log Table Title
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "REKAPITULASI KEHADIRAN PEGAWAI WFH",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = selectedMonth,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary
            )
        }

        // List View
        if (filteredRecords.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text("Tidak ada data rekapitulasi absensi.", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredRecords) { record ->
                    AdminAttendanceRowCard(record = record)
                }
            }
        }
    }
}

@Composable
fun StatCard(
    title: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = color)
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = color)
        }
    }
}

@Composable
fun AdminAttendanceRowCard(record: AttendanceSummary) {
    val isMasuk = record.jenisAbsensi.contains("MASUK", ignoreCase = true)

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = record.namaLengkap,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )

                Surface(
                    color = if (isMasuk) Color(0xFF10B981).copy(alpha = 0.15f) else Color(0xFF38BDF8).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = record.jenisAbsensi,
                        color = if (isMasuk) Color(0xFF10B981) else Color(0xFF0284C7),
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = "NIP: ${record.nip} • ${record.jabatan}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column {
                        Text("JAM MASUK", fontSize = 9.sp, color = MaterialTheme.colorScheme.outline)
                        Text(if (record.jamMasuk.isNotBlank()) record.jamMasuk else "-", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Column {
                        Text("JAM PULANG", fontSize = 9.sp, color = MaterialTheme.colorScheme.outline)
                        Text(if (record.jamPulang.isNotBlank()) record.jamPulang else "-", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (record.isSyncedToSheets) Icons.Default.CloudDone else Icons.Default.CloudQueue,
                        contentDescription = null,
                        tint = if (record.isSyncedToSheets) Color(0xFF10B981) else Color(0xFFF59E0B),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = if (record.isSyncedToSheets) "Ter-Sync Sheets" else "Pending Sync",
                        fontSize = 10.sp,
                        color = if (record.isSyncedToSheets) Color(0xFF10B981) else Color(0xFFF59E0B),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
