package com.example

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.screens.*
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.AttendanceViewModel

enum class NavigationTab(val title: String, val icon: ImageVector, val isAdminOnly: Boolean) {
    FORM("Absensi WFH", Icons.Default.HowToReg, false),
    HISTORY("Riwayat", Icons.Default.History, false),
    ADMIN_DASHBOARD("Monitoring", Icons.Default.Dashboard, true),
    ADMIN_USERS("Hak Akses", Icons.Default.ManageAccounts, true),
    SETTINGS("Pengaturan", Icons.Default.Settings, true)
}

class MainActivity : ComponentActivity() {

    private val viewModel: AttendanceViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                var showSplash by remember { mutableStateOf(true) }

                // Android 13+ (API 33) requires this runtime permission before ANY
                // notification -- including the daily attendance reminder -- can actually
                // be shown. Previously this was only requested from inside
                // AdminSettingsScreen, which a regular employee never opens, so the
                // reminder silently never appeared for anyone but the admin's own device.
                // Requesting it here, once, for every user on first launch is what makes
                // the "on by default" reminder in AttendanceViewModel actually reach
                // employees' phones. Uses the Compose launcher (like AdminSettingsScreen
                // already does) rather than ComponentActivity.registerForActivityResult,
                // which lint flags as requiring a newer Fragment version than this project
                // pins (InvalidFragmentVersionForActivityResult).
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
                ) { /* No extra action needed either way: showAbsensiReminder() already
                       checks the permission itself and skips gracefully if denied. */ }
                val context = LocalContext.current
                LaunchedEffect(Unit) {
                    if (android.os.Build.VERSION.SDK_INT >= 33 &&
                        androidx.core.content.ContextCompat.checkSelfPermission(
                            context, android.Manifest.permission.POST_NOTIFICATIONS
                        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                if (showSplash) {
                    com.example.ui.screens.SplashScreen(onFinished = { showSplash = false })
                } else {
                    MainAppStructure(viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppStructure(viewModel: AttendanceViewModel) {
    val currentTabIndex by viewModel.currentTab.collectAsState()
    val isAdminAuthenticated by viewModel.isAdminAuthenticated.collectAsState()

    val currentTab = NavigationTab.entries.getOrNull(currentTabIndex) ?: NavigationTab.FORM

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF0F172A),
                contentColor = Color.White,
                modifier = Modifier.navigationBarsPadding()
            ) {
                NavigationTab.entries.forEach { tab ->
                    val isSelected = currentTabIndex == tab.ordinal
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = {
                            viewModel.currentTab.value = tab.ordinal
                        },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.title,
                                tint = if (isSelected) Color(0xFFF59E0B) else Color(0xFF94A3B8)
                            )
                        },
                        label = {
                            Text(
                                text = tab.title,
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) Color(0xFFF59E0B) else Color(0xFF94A3B8)
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = Color(0xFF1E293B)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFFF8FAFC))
        ) {
            when (currentTab) {
                NavigationTab.FORM -> UserFormScreen(viewModel)
                NavigationTab.HISTORY -> UserHistoryScreen(viewModel)
                NavigationTab.ADMIN_DASHBOARD -> {
                    if (isAdminAuthenticated) {
                        AdminDashboardScreen(viewModel)
                    } else {
                        AdminLoginScreen(
                            viewModel = viewModel,
                            onLoginSuccess = { viewModel.currentTab.value = NavigationTab.ADMIN_DASHBOARD.ordinal },
                            onBackToUserForm = { viewModel.currentTab.value = NavigationTab.FORM.ordinal }
                        )
                    }
                }
                NavigationTab.ADMIN_USERS -> {
                    if (isAdminAuthenticated) {
                        AdminUserManagementScreen(viewModel)
                    } else {
                        AdminLoginScreen(
                            viewModel = viewModel,
                            onLoginSuccess = { viewModel.currentTab.value = NavigationTab.ADMIN_USERS.ordinal },
                            onBackToUserForm = { viewModel.currentTab.value = NavigationTab.FORM.ordinal }
                        )
                    }
                }
                NavigationTab.SETTINGS -> {
                    if (isAdminAuthenticated) {
                        AdminSettingsScreen(viewModel)
                    } else {
                        AdminLoginScreen(
                            viewModel = viewModel,
                            onLoginSuccess = { viewModel.currentTab.value = NavigationTab.SETTINGS.ordinal },
                            onBackToUserForm = { viewModel.currentTab.value = NavigationTab.FORM.ordinal }
                        )
                    }
                }
            }
        }
    }
}
