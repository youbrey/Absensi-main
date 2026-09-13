package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.example.data.UserEntity
import com.example.ui.viewmodel.AttendanceViewModel

@Composable
fun AdminUserManagementScreen(
    viewModel: AttendanceViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val allUsers by viewModel.allUsersList.collectAsState()

    var showAddUserDialog by remember { mutableStateOf(false) }

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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF0284C7)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.ManageAccounts,
                            contentDescription = null,
                            tint = Color.White
                        )
                    }

                    Column {
                        Text(
                            text = "KELOLA HAK AKSES PEGAWAI",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Akses PNS, PPPK & Hak Istimewa Sistem",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(
                        onClick = { showAddUserDialog = true },
                        colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFF10B981))
                    ) {
                        Icon(
                            Icons.Default.PersonAdd,
                            contentDescription = "Tambah Pegawai",
                            tint = Color.White
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
        }

        Text(
            text = "DAFTAR PEGAWAI PNS & PPPK DPRD BITUNG (${allUsers.size} Pengguna)",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(allUsers) { user ->
                UserPrivilegeCard(
                    user = user,
                    onToggleActive = { viewModel.toggleUserActiveState(user) }
                )
            }
        }
    }

    if (showAddUserDialog) {
        AddUserDialog(
            onDismiss = { showAddUserDialog = false },
            onSave = { nama, nip, jabatan, tipe, onResult ->
                viewModel.saveNewUser(nama, nip, jabatan, tipe) { error ->
                    onResult(error)
                    if (error == null) showAddUserDialog = false
                }
            }
        )
    }
}

@Composable
fun UserPrivilegeCard(
    user: UserEntity,
    onToggleActive: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                    Surface(
                        color = if (user.tipePegawai == "PNS") Color(0xFF0284C7).copy(alpha = 0.15f) else Color(0xFFD97706).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = user.tipePegawai,
                            color = if (user.tipePegawai == "PNS") Color(0xFF0284C7) else Color(0xFFD97706),
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }

                    if (user.role == "ADMIN") {
                        Surface(
                            color = Color(0xFFDC2626).copy(alpha = 0.15f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "ADMIN",
                                color = Color(0xFFDC2626),
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Switch(
                    checked = user.isActive,
                    onCheckedChange = { onToggleActive() }
                )
            }

            Text(
                text = user.namaLengkap,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )

            Text(
                text = "NIP: ${user.nip} • ${user.jabatan}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (user.role == "ADMIN") Icons.Default.Key else Icons.Default.KeyOff,
                        contentDescription = null,
                        tint = if (user.role == "ADMIN") Color(0xFFF59E0B) else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = if (user.role == "ADMIN") "Pengaturan jadwal melalui login admin" else "Sesuai Jadwal Operasional",
                        fontSize = 10.sp,
                        color = if (user.role == "ADMIN") Color(0xFFF59E0B) else MaterialTheme.colorScheme.outline,
                        fontWeight = FontWeight.Medium
                    )
                }

                Text(
                    text = if (user.isActive) "Status: AKTIFF" else "Status: NONAKTIF",
                    fontSize = 10.sp,
                    color = if (user.isActive) Color(0xFF10B981) else Color(0xFFEF4444),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun AddUserDialog(
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, (String?) -> Unit) -> Unit
) {
    var namaInput by remember { mutableStateOf("") }
    var nipInput by remember { mutableStateOf("") }
    var jabatanInput by remember { mutableStateOf("") }
    var tipeSelected by remember { mutableStateOf("PNS") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tambah Pegawai Baru", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = namaInput,
                    onValueChange = { namaInput = it },
                    label = { Text("Nama Lengkap & Gelar") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = nipInput,
                    onValueChange = { nipInput = it },
                    label = { Text("NIP") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = jabatanInput,
                    onValueChange = { jabatanInput = it },
                    label = { Text("Jabatan / Unit Kerja") },
                    singleLine = true
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = tipeSelected == "PNS",
                        onClick = { tipeSelected = "PNS" },
                        label = { Text("PNS") }
                    )
                    FilterChip(
                        selected = tipeSelected == "PPPK",
                        onClick = { tipeSelected = "PPPK" },
                        label = { Text("PPPK") }
                    )
                }

                // Pegawai selalu ditambahkan sebagai USER: absensi tidak memerlukan login,
                // dan akun ADMIN tidak lagi bisa dibuat dari dalam aplikasi -- admin diatur
                // hanya di server (lihat backend/Code.gs, fungsi setupAdminAccount).
                Text(
                    text = "Pegawai tidak memerlukan kata sandi — absensi dilakukan tanpa login.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = !saving,
                onClick = {
                    if (namaInput.isNotBlank() && nipInput.isNotBlank()) {
                        saving = true
                        onSave(namaInput, nipInput, jabatanInput, tipeSelected) {
                            error = it; saving = false
                        }
                    } else {
                        error = "Lengkapi nama dan NIP"
                    }
                }
            ) {
                Text("Simpan")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal")
            }
        }
    )
}
