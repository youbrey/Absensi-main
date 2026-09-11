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

    var passwordUser by remember { mutableStateOf<UserEntity?>(null) }
    var passwordInput by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var resetting by remember { mutableStateOf(false) }
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
                    onToggleActive = { viewModel.toggleUserActiveState(user) },
                    onResetPassword = if (user.role == "ADMIN") {
                        { passwordUser = user; passwordInput = ""; passwordError = null }
                    } else null
                )
            }
        }
    }

    passwordUser?.let { user ->
        AlertDialog(onDismissRequest = { if (!resetting) passwordUser = null },
            title = { Text("Atur kata sandi ${user.namaLengkap}") },
            text = { Column {
                OutlinedTextField(value = passwordInput, onValueChange = { passwordInput = it },
                    label = { Text("Kata sandi baru (minimal 8 karakter)") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                passwordError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(enabled = !resetting, onClick = {
                resetting = true
                viewModel.resetUserPassword(user, passwordInput) { error ->
                    resetting = false; passwordError = error
                    if (error == null) passwordUser = null
                }
            }) { Text("Simpan") } },
            dismissButton = { TextButton(enabled = !resetting, onClick = { passwordUser = null }) { Text("Batal") } })
    }

    if (showAddUserDialog) {
        AddUserDialog(
            onDismiss = { showAddUserDialog = false },
            onSave = { nama, nip, jabatan, tipe, role, password, onResult ->
                viewModel.saveNewUser(nama, nip, jabatan, tipe, role, password) { error ->
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
    onToggleActive: () -> Unit,
    onResetPassword: (() -> Unit)?
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
            onResetPassword?.let { reset ->
                TextButton(onClick = reset) { Text("Atur kata sandi") }
            }
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
    onSave: (String, String, String, String, String, String, (String?) -> Unit) -> Unit
) {
    var namaInput by remember { mutableStateOf("") }
    var nipInput by remember { mutableStateOf("") }
    var jabatanInput by remember { mutableStateOf("") }
    var tipeSelected by remember { mutableStateOf("PNS") }
    var roleSelected by remember { mutableStateOf("USER") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tambah Pegawai / Akses Baru", fontWeight = FontWeight.Bold) },
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

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = roleSelected == "USER",
                        onClick = { roleSelected = "USER" },
                        label = { Text("USER") }
                    )
                    FilterChip(
                        selected = roleSelected == "ADMIN",
                        onClick = { roleSelected = "ADMIN" },
                        label = { Text("ADMIN") }
                    )
                }

                // Pegawai biasa (USER) tidak butuh kata sandi karena absensi tidak memerlukan
                // login. Kata sandi hanya diperlukan untuk akun ADMIN yang membuka Monitoring,
                // Hak Akses, dan Pengaturan (termasuk mode pembatasan waktu finger).
                if (roleSelected == "ADMIN") {
                    OutlinedTextField(value = password, onValueChange = { password = it },
                        label = { Text("Kata sandi admin (minimal 8 karakter)") }, singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                } else {
                    Text(
                        text = "Pegawai USER tidak memerlukan kata sandi — absensi dilakukan tanpa login.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = !saving,
                onClick = {
                    if (namaInput.isNotBlank() && nipInput.isNotBlank() &&
                        (roleSelected != "ADMIN" || password.length >= 8)) {
                        saving = true
                        onSave(namaInput, nipInput, jabatanInput, tipeSelected, roleSelected, password) {
                            error = it; saving = false
                        }
                    } else if (roleSelected == "ADMIN") {
                        error = "Kata sandi admin minimal 8 karakter"
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
