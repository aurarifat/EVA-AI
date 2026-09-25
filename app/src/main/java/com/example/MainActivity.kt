package com.example

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import android.content.Context
import android.content.Intent
import androidx.navigation.compose.rememberNavController
import com.example.eva.overlay.EvaOverlayService
import com.example.eva.ui.EvaViewModel
import com.example.eva.ui.screens.*
import com.example.ui.theme.EvaObsidian
import com.example.ui.theme.EvaSurface
import com.example.ui.theme.EvaYellowPrimary
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_MANUAL_OPEN = "com.example.eva.EXTRA_MANUAL_OPEN"
        const val PREFS_NAME = "eva_settings"
        const val KEY_AUTO_TOGGLE_HOME = "auto_toggle_home"

        fun isAutoToggleHomeEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_AUTO_TOGGLE_HOME, true)
        }

        fun setAutoToggleHomeEnabled(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_AUTO_TOGGLE_HOME, enabled).apply()
        }

        fun toggleToHomeScreen(context: Context) {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                context.startActivity(homeIntent)
            } catch (_: Exception) {
                if (context is ComponentActivity) {
                    context.moveTaskToBack(true)
                }
            }
        }
    }

    private val viewModel: EvaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // When the user clicks the app, if auto-toggle is active, automatically start the overlay and toggle to home screen
        if (savedInstanceState == null) {
            handleLaunchIntent(intent)
        }

        setContent {
            MyApplicationTheme {
                // Runtime Permission Request for Microphone & Notifications
                val permissionsToRequest = remember {
                    val list = mutableListOf(
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.CAMERA
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        list.add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    list.toTypedArray()
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { /* permissions handled gracefully */ }

                LaunchedEffect(Unit) {
                    permissionLauncher.launch(permissionsToRequest)
                }

                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route ?: "home"

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        // Bottom Navigation Bar for primary views
                        if (currentRoute in listOf("home", "chat", "tools", "schedule", "settings")) {
                            NavigationBar(
                                containerColor = EvaSurface,
                                tonalElevation = 4.dp
                            ) {
                                NavigationBarItem(
                                    selected = currentRoute == "home",
                                    onClick = { navController.navigate("home") { popUpTo("home") { inclusive = true } } },
                                    icon = { Icon(Icons.Default.GraphicEq, contentDescription = "Home") },
                                    label = { Text("EVA", fontSize = 11.sp) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Color(0xFF090A0E),
                                        selectedTextColor = EvaYellowPrimary,
                                        indicatorColor = EvaYellowPrimary,
                                        unselectedIconColor = Color(0xFF94A3B8),
                                        unselectedTextColor = Color(0xFF94A3B8)
                                    )
                                )
                                NavigationBarItem(
                                    selected = currentRoute == "chat",
                                    onClick = { navController.navigate("chat") },
                                    icon = { Icon(Icons.Default.ChatBubbleOutline, contentDescription = "Chat") },
                                    label = { Text("Chat", fontSize = 11.sp) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Color(0xFF090A0E),
                                        selectedTextColor = EvaYellowPrimary,
                                        indicatorColor = EvaYellowPrimary,
                                        unselectedIconColor = Color(0xFF94A3B8),
                                        unselectedTextColor = Color(0xFF94A3B8)
                                    )
                                )
                                NavigationBarItem(
                                    selected = currentRoute == "tools",
                                    onClick = { navController.navigate("tools") },
                                    icon = { Icon(Icons.Default.Build, contentDescription = "Tools") },
                                    label = { Text("Tools", fontSize = 11.sp) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Color(0xFF090A0E),
                                        selectedTextColor = EvaYellowPrimary,
                                        indicatorColor = EvaYellowPrimary,
                                        unselectedIconColor = Color(0xFF94A3B8),
                                        unselectedTextColor = Color(0xFF94A3B8)
                                    )
                                )
                                NavigationBarItem(
                                    selected = currentRoute == "schedule",
                                    onClick = { navController.navigate("schedule") },
                                    icon = { Icon(Icons.Default.CalendarToday, contentDescription = "Schedule") },
                                    label = { Text("Schedule", fontSize = 11.sp) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Color(0xFF090A0E),
                                        selectedTextColor = EvaYellowPrimary,
                                        indicatorColor = EvaYellowPrimary,
                                        unselectedIconColor = Color(0xFF94A3B8),
                                        unselectedTextColor = Color(0xFF94A3B8)
                                    )
                                )
                                NavigationBarItem(
                                    selected = currentRoute == "settings",
                                    onClick = { navController.navigate("settings") },
                                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                                    label = { Text("Settings", fontSize = 11.sp) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Color(0xFF090A0E),
                                        selectedTextColor = EvaYellowPrimary,
                                        indicatorColor = EvaYellowPrimary,
                                        unselectedIconColor = Color(0xFF94A3B8),
                                        unselectedTextColor = Color(0xFF94A3B8)
                                    )
                                )
                            }
                        }
                    },
                    containerColor = EvaObsidian
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        NavHost(
                            navController = navController,
                            startDestination = "home"
                        ) {
                            composable("home") {
                                HomeScreen(
                                    viewModel = viewModel,
                                    onNavigateToChat = { navController.navigate("chat") },
                                    onNavigateToTools = { navController.navigate("tools") },
                                    onNavigateToApps = { navController.navigate("apps") },
                                    onNavigateToShizuku = { navController.navigate("shizuku") },
                                    onNavigateToSchedule = { navController.navigate("schedule") },
                                    onNavigateToSettings = { navController.navigate("settings") }
                                )
                            }
                            composable("chat") {
                                ConversationScreen(
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() }
                                )
                            }
                            composable("tools") {
                                ToolsScreen(
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() },
                                    onNavigateToShizuku = { navController.navigate("shizuku") },
                                    onNavigateToApps = { navController.navigate("apps") },
                                    onNavigateToSchedule = { navController.navigate("schedule") }
                                )
                            }
                            composable("shizuku") {
                                ShizukuScreen(
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() }
                                )
                            }
                            composable("apps") {
                                AppLauncherScreen(
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() }
                                )
                            }
                            composable("schedule") {
                                ScheduleScreen(
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() }
                                )
                            }
                            composable("settings") {
                                SettingsScreen(
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() },
                                    onNavigateToPrivacy = { navController.navigate("privacy") },
                                    onNavigateToShizuku = { navController.navigate("shizuku") }
                                )
                            }
                            composable("privacy") {
                                PrivacyScreen(
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshShizukuStatus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        val manualOpen = intent?.getBooleanExtra(EXTRA_MANUAL_OPEN, false) ?: false
        val autoToggle = isAutoToggleHomeEnabled(this)

        if (autoToggle && !manualOpen && EvaOverlayService.isOverlayPermissionGranted(this)) {
            EvaOverlayService.startOverlay(this)
            toggleToHomeScreen(this)
        }
    }
}
