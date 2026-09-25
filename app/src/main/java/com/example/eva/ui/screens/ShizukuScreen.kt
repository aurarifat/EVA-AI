package com.example.eva.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.eva.shizuku.AdbCapability
import com.example.eva.shizuku.ShizukuConnectionStatus
import com.example.eva.shizuku.WirelessSessionStatus
import com.example.eva.ui.EvaViewModel
import com.example.eva.ui.components.EvaGlassCard
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShizukuScreen(
    viewModel: EvaViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shizukuInfo by viewModel.shizukuState.collectAsState()
    val wirelessSession by viewModel.wirelessSession.collectAsState()
    val shizukuLogs by viewModel.shizukuLogs.collectAsState()
    val shizukuManager = viewModel.shizukuManager
    val adbManager = viewModel.adbCapabilityManager

    var wirelessPortText by remember { mutableStateOf("") }
    var portTestResult by remember { mutableStateOf<String?>(null) }
    var isTestingPort by remember { mutableStateOf(false) }
    var isAutoDetectingPort by remember { mutableStateOf(false) }
    var isConnectingSession by remember { mutableStateOf(false) }
    var isPingingSession by remember { mutableStateOf(false) }

    var isTogglingNativeAdb by remember { mutableStateOf(false) }

    var selectedCapability by remember { mutableStateOf<AdbCapability?>(null) }
    var adbOutputText by remember { mutableStateOf<String?>(null) }
    var isExecutingAdb by remember { mutableStateOf(false) }
    var adbExecTimeMs by remember { mutableLongStateOf(0L) }
    var adbExitCode by remember { mutableIntStateOf(0) }

    var showTroubleshooting by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(true) }

    val capabilities = remember { adbManager.getWhitelistedCapabilities() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Shizuku & Wireless Debugging",
                        color = EvaTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = EvaTextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.refreshShizukuStatus()
                        Toast.makeText(context, "Shizuku connection refreshed", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = EvaYellowPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = EvaObsidian)
            )
        },
        containerColor = EvaObsidian
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Shizuku Primary Connection Status Card
            EvaGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Cable,
                                contentDescription = null,
                                tint = EvaYellowGold,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "SHIZUKU CONNECTION STATUS",
                                color = EvaYellowGold,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }

                        val (statusBadge, badgeColor) = when (shizukuInfo.status) {
                            ShizukuConnectionStatus.AUTHORIZED_CONNECTED -> "CONNECTED" to EvaSuccessGreen
                            ShizukuConnectionStatus.PERMISSION_REQUIRED -> "AUTH NEEDED" to EvaYellowBright
                            ShizukuConnectionStatus.SERVER_NOT_RUNNING -> "DISCONNECTED" to EvaErrorRed
                            ShizukuConnectionStatus.NOT_INSTALLED -> "NOT INSTALLED" to Color(0xFF64748B)
                            else -> "UNKNOWN" to Color(0xFF64748B)
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = badgeColor.copy(alpha = 0.2f),
                            border = BorderStroke(1.dp, badgeColor)
                        ) {
                            Text(
                                text = statusBadge,
                                color = badgeColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = shizukuInfo.lastPingMessage,
                        color = EvaTextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Detail Specs Grid
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = EvaSurfaceElevated,
                        border = BorderStroke(1.dp, Color(0x1AFFFFFF)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Server Version", color = EvaTextTertiary, fontSize = 12.sp)
                                Text(
                                    text = if (shizukuInfo.serverVersion > 0) "v${shizukuInfo.serverVersion}" else "N/A",
                                    color = EvaTextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Execution Mode", color = EvaTextTertiary, fontSize = 12.sp)
                                val modeText = when (shizukuInfo.serverUid) {
                                    0 -> "Root (UID 0)"
                                    2000 -> "Wireless Debugging Shell (UID 2000)"
                                    -1 -> "Offline / Stopped"
                                    else -> "UID: ${shizukuInfo.serverUid}"
                                }
                                Text(
                                    text = modeText,
                                    color = if (shizukuInfo.isWirelessDebuggingAdbMode) EvaCyanAccent else EvaYellowPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Binder Token", color = EvaTextTertiary, fontSize = 12.sp)
                                Text(
                                    text = if (shizukuInfo.isBinderAlive) "Active & Alive" else "Dead / Inactive",
                                    color = if (shizukuInfo.isBinderAlive) EvaSuccessGreen else EvaErrorRed,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("EVA Authorization", color = EvaTextTertiary, fontSize = 12.sp)
                                Text(
                                    text = if (shizukuInfo.isAuthorized) "Granted" else "Requires Authorization",
                                    color = if (shizukuInfo.isAuthorized) EvaSuccessGreen else EvaYellowBright,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Action buttons depending on state
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (shizukuInfo.isBinderAlive && !shizukuInfo.isAuthorized) {
                            Button(
                                onClick = {
                                    val success = viewModel.requestShizukuAuthorization()
                                    if (!success) {
                                        Toast.makeText(context, "Could not launch Shizuku permission request", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = EvaYellowPrimary, contentColor = Color(0xFF090A0E)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Authorize EVA", fontWeight = FontWeight.Bold)
                            }
                        }

                        val shizukuLaunchIntent = shizukuManager.openShizukuApp()
                        if (shizukuLaunchIntent != null) {
                            OutlinedButton(
                                onClick = { context.startActivity(shizukuLaunchIntent) },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = EvaTextPrimary),
                                border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Launch, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Open Shizuku")
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app"))
                                        context.startActivity(intent)
                                    } catch (_: Exception) {}
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = EvaCyanAccent),
                                border = BorderStroke(1.dp, Color(0x3338BDF8)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Get Shizuku")
                            }
                        }
                    }
                }
            }

            // 2. Wireless Debugging Bridge & Active Session Card
            val isSessionActive = wirelessSession.sessionStatus == WirelessSessionStatus.ACTIVE_CONNECTED
            val isSessionConnecting = wirelessSession.sessionStatus == WirelessSessionStatus.CONNECTING
            val isSessionError = wirelessSession.sessionStatus == WirelessSessionStatus.ERROR

            EvaGlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        BorderStroke(
                            1.dp,
                            if (isSessionActive) EvaCyanAccent else Color(0x22FFFFFF)
                        ),
                        RoundedCornerShape(16.dp)
                    )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.WifiTethering,
                                contentDescription = null,
                                tint = if (isSessionActive) EvaCyanAccent else EvaYellowGold,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "WIRELESS DEBUGGING BRIDGE SESSION",
                                color = if (isSessionActive) EvaCyanAccent else EvaYellowGold,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }

                        val sessionBadgeColor = when (wirelessSession.sessionStatus) {
                            WirelessSessionStatus.ACTIVE_CONNECTED -> EvaCyanAccent
                            WirelessSessionStatus.CONNECTING -> EvaYellowBright
                            WirelessSessionStatus.ERROR -> EvaErrorRed
                            WirelessSessionStatus.DISCONNECTED -> Color(0xFF64748B)
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = sessionBadgeColor.copy(alpha = 0.2f),
                            border = BorderStroke(1.dp, sessionBadgeColor)
                        ) {
                            Text(
                                text = when (wirelessSession.sessionStatus) {
                                    WirelessSessionStatus.ACTIVE_CONNECTED -> "BRIDGE ACTIVE"
                                    WirelessSessionStatus.CONNECTING -> "CONNECTING"
                                    WirelessSessionStatus.ERROR -> "SESSION ERROR"
                                    WirelessSessionStatus.DISCONNECTED -> "DISCONNECTED"
                                },
                                color = sessionBadgeColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = wirelessSession.statusMessage,
                        color = EvaTextPrimary,
                        fontSize = 13.sp
                    )

                    if (isSessionError && wirelessSession.lastError != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Error detail: ${wirelessSession.lastError}",
                            color = EvaErrorRed,
                            fontSize = 12.sp
                        )
                    }

                    if (isSessionActive) {
                        Spacer(modifier = Modifier.height(12.dp))
                        // Active Session Telemetry Grid
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = EvaSurfaceElevated,
                            border = BorderStroke(1.dp, Color(0x3338BDF8)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Local Bridge Endpoint", color = EvaTextTertiary, fontSize = 12.sp)
                                    Text(
                                        "127.0.0.1:${wirelessSession.activePort ?: 5555}",
                                        color = EvaCyanAccent,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Round-trip Latency", color = EvaTextTertiary, fontSize = 12.sp)
                                    Text(
                                        "${wirelessSession.latencyMs} ms",
                                        color = if (wirelessSession.latencyMs in 0..100) EvaSuccessGreen else EvaYellowBright,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Commands Executed", color = EvaTextTertiary, fontSize = 12.sp)
                                    Text(
                                        "${wirelessSession.commandsExecutedCount}",
                                        color = EvaTextPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Device Wi-Fi IP", color = EvaTextTertiary, fontSize = 12.sp)
                                    Text(
                                        wirelessSession.localIpAddress,
                                        color = EvaTextSecondary,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                onClick = {
                                    isPingingSession = true
                                    viewModel.pingWirelessSession { ok, lat ->
                                        isPingingSession = false
                                        val toastMsg = if (ok) "Ping OK: ${lat}ms" else "Ping failed"
                                        Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = EvaSurfaceElevated, contentColor = EvaCyanAccent),
                                border = BorderStroke(1.dp, Color(0x3338BDF8)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (isPingingSession) "Pinging..." else "Ping Bridge", fontSize = 12.sp)
                            }

                            Button(
                                onClick = { viewModel.disconnectWirelessSession() },
                                colors = ButtonDefaults.buttonColors(containerColor = EvaErrorRed.copy(alpha = 0.8f), contentColor = Color.White),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Disconnect", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.height(12.dp))

                        // Connect button and auto-detect
                        Button(
                            onClick = {
                                isConnectingSession = true
                                val portVal = wirelessPortText.toIntOrNull()
                                viewModel.establishWirelessSession(portVal) { success, msg ->
                                    isConnectingSession = false
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = !isConnectingSession && shizukuInfo.isAuthorized,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EvaCyanAccent,
                                contentColor = Color(0xFF090A0E)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isConnectingSession) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color(0xFF090A0E), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Establishing Bridge Session...", fontWeight = FontWeight.Bold)
                            } else {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Start Wireless Debugging Bridge", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // 3. Android 11+ Native Wireless Debugging Setting & Port Config
            EvaGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "ANDROID 11+ NATIVE WIRELESS DEBUGGING",
                        color = EvaYellowGold,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Android 11+ provides native Wireless Debugging. Shizuku's shell bridge allows toggling system properties and inspecting ADB ports.",
                        color = EvaTextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Switch Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(EvaSurfaceElevated, RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Native Wireless Debugging",
                                color = EvaTextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (wirelessSession.isNativeAdbWifiEnabled) "Status: Enabled in Settings.Global" else "Status: Disabled or inactive",
                                color = if (wirelessSession.isNativeAdbWifiEnabled) EvaSuccessGreen else EvaTextTertiary,
                                fontSize = 11.sp
                            )
                        }

                        Switch(
                            checked = wirelessSession.isNativeAdbWifiEnabled,
                            onCheckedChange = { enable ->
                                isTogglingNativeAdb = true
                                scope.launch {
                                    viewModel.toggleNativeWirelessDebugging(enable) { ok, msg ->
                                        isTogglingNativeAdb = false
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            enabled = shizukuInfo.isAuthorized && !isTogglingNativeAdb,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = EvaYellowPrimary,
                                checkedTrackColor = EvaYellowPrimary.copy(alpha = 0.5f),
                                uncheckedThumbColor = Color.Gray,
                                uncheckedTrackColor = EvaSurfaceHighlight
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = {
                                try {
                                    context.startActivity(shizukuManager.openWirelessDebuggingSettings())
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Could not open developer options: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = EvaYellowPrimary),
                            border = BorderStroke(1.dp, Color(0x33FFD54F)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.DeveloperMode, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Developer Options", fontSize = 11.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val (ok, msg) = shizukuManager.configureAdbTcpPort(5555)
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    if (ok) wirelessPortText = "5555"
                                }
                            },
                            enabled = shizukuInfo.isAuthorized,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = EvaCyanAccent),
                            border = BorderStroke(1.dp, Color(0x3338BDF8)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.SettingsEthernet, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Set Port 5555", fontSize = 11.sp)
                        }
                    }
                }
            }

            // 4. Port Auto-Detection & Socket Connectivity Tester
            EvaGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "ADB PORT DISCOVERY & SOCKET TESTER",
                        color = EvaYellowGold,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Discover or verify the local TCP port listening for ADB / Wireless Debugging connections:",
                        color = EvaTextSecondary,
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = wirelessPortText,
                            onValueChange = { wirelessPortText = it },
                            placeholder = { Text("Port (e.g. 5555, 37015)", fontSize = 12.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = EvaYellowPrimary,
                                unfocusedBorderColor = Color(0x33FFFFFF)
                            )
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        OutlinedButton(
                            onClick = {
                                isAutoDetectingPort = true
                                viewModel.autoDetectPort { detected ->
                                    isAutoDetectingPort = false
                                    if (detected != null) {
                                        wirelessPortText = detected.toString()
                                        portTestResult = "Detected active wireless debugging port: $detected"
                                    } else {
                                        portTestResult = "No active listening port discovered. Start Wireless Debugging first."
                                    }
                                }
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = EvaYellowBright),
                            border = BorderStroke(1.dp, Color(0x33FFD54F)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Text(if (isAutoDetectingPort) "Scanning..." else "Auto-Detect", fontSize = 11.sp)
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        Button(
                            onClick = {
                                val p = wirelessPortText.toIntOrNull()
                                if (p != null) {
                                    isTestingPort = true
                                    portTestResult = null
                                    scope.launch {
                                        val (success, msg) = shizukuManager.testWirelessDebuggingPort(p)
                                        isTestingPort = false
                                        portTestResult = msg
                                    }
                                } else {
                                    portTestResult = "Please enter a valid port number (1024-65535)."
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = EvaYellowPrimary, contentColor = Color(0xFF090A0E)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(if (isTestingPort) "Testing..." else "Test Port", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (portTestResult != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = EvaSurfaceElevated,
                            border = BorderStroke(1.dp, if (portTestResult?.contains("active") == true || portTestResult?.contains("Detected") == true) EvaSuccessGreen else EvaErrorRed),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = portTestResult ?: "",
                                color = if (portTestResult?.contains("active") == true || portTestResult?.contains("Detected") == true) EvaSuccessGreen else EvaErrorRed,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }

            // 5. Authorized ADB Whitelisted Capabilities Runner
            EvaGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "AUTHORIZED CAPABILITY RUNNER",
                        color = EvaYellowGold,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "EVA executes verified system diagnostics through Shizuku's privileged IPC process interface. Arbitrary commands are restricted.",
                        color = EvaTextTertiary,
                        fontSize = 11.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    for (cap in capabilities) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = EvaSurfaceElevated,
                            border = BorderStroke(1.dp, Color(0x22FFFFFF)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(12.dp)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(cap.toolName, color = EvaTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text(cap.description, color = EvaTextSecondary, fontSize = 11.sp)
                                    Text("Command: ${cap.command.joinToString(" ")}", color = EvaYellowBright, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                Button(
                                    onClick = {
                                        selectedCapability = cap
                                        isExecutingAdb = true
                                        adbOutputText = null
                                        scope.launch {
                                            val res = adbManager.executeWhitelistedCapability(cap.id)
                                            isExecutingAdb = false
                                            adbOutputText = res.output
                                            adbExecTimeMs = res.executionTimeMs
                                            adbExitCode = res.exitCode
                                        }
                                    },
                                    enabled = shizukuInfo.isAuthorized && !isExecutingAdb,
                                    colors = ButtonDefaults.buttonColors(containerColor = EvaYellowPrimary, contentColor = Color(0xFF090A0E)),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text("Run", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    // Terminal Output Window
                    if (adbOutputText != null || isExecutingAdb) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "TERMINAL EXECUTION OUTPUT",
                                color = EvaCyanAccent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (adbOutputText != null) {
                                Text(
                                    text = "Exit Code: $adbExitCode | ${adbExecTimeMs}ms",
                                    color = if (adbExitCode == 0) EvaSuccessGreen else EvaErrorRed,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF050608), RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0x3338BDF8), RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Column {
                                Text(
                                    text = if (isExecutingAdb) "Executing through Shizuku IPC binder token..." else (adbOutputText ?: ""),
                                    color = Color(0xFFE2E8F0),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 16.sp
                                )

                                if (adbOutputText != null) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        TextButton(
                                            onClick = {
                                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                cm.setPrimaryClip(ClipData.newPlainText("ADB Output", adbOutputText))
                                                Toast.makeText(context, "Output copied to clipboard", Toast.LENGTH_SHORT).show()
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = null, tint = EvaCyanAccent, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Copy Output", color = EvaCyanAccent, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 6. Live IPC & Bridge Event Logs Console
            EvaGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Terminal, contentDescription = null, tint = EvaYellowGold, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "REAL-TIME DIAGNOSTIC LOGS (${shizukuLogs.size})",
                                color = EvaYellowGold,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }

                        Row {
                            TextButton(
                                onClick = { showLogs = !showLogs },
                                contentPadding = PaddingValues(horizontal = 6.dp)
                            ) {
                                Text(if (showLogs) "Hide" else "Show", color = EvaYellowPrimary, fontSize = 11.sp)
                            }
                            TextButton(
                                onClick = { viewModel.clearShizukuLogs() },
                                contentPadding = PaddingValues(horizontal = 6.dp)
                            ) {
                                Text("Clear", color = EvaTextTertiary, fontSize = 11.sp)
                            }
                        }
                    }

                    if (showLogs) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 100.dp, max = 220.dp)
                                .background(Color(0xFF050608), RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(8.dp))
                                .padding(8.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            if (shizukuLogs.isEmpty()) {
                                Text(
                                    "No log events recorded yet. Perform an action to see real-time binder events.",
                                    color = EvaTextTertiary,
                                    fontSize = 11.sp
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    for (entry in shizukuLogs.takeLast(40)) {
                                        val levelColor = when (entry.level) {
                                            "SUCCESS" -> EvaSuccessGreen
                                            "WARN" -> EvaYellowBright
                                            "ERROR" -> EvaErrorRed
                                            else -> EvaCyanAccent
                                        }
                                        Row(modifier = Modifier.fillMaxWidth()) {
                                            Text(
                                                text = "${entry.formattedTime} ",
                                                color = EvaTextTertiary,
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                            Text(
                                                text = "[${entry.level}] ",
                                                color = levelColor,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace
                                            )
                                            Text(
                                                text = entry.message,
                                                color = EvaTextPrimary,
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                lineHeight = 14.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 7. Step-by-Step Setup Guide & Troubleshooting Accordion
            EvaGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showTroubleshooting = !showTroubleshooting },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.HelpOutline, contentDescription = null, tint = EvaYellowGold, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "PAIRING GUIDE & TROUBLESHOOTING",
                                color = EvaYellowGold,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }

                        Icon(
                            imageVector = if (showTroubleshooting) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = EvaTextSecondary
                        )
                    }

                    if (showTroubleshooting) {
                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "How to start Shizuku with Wireless Debugging on Android 11+ (No PC required):",
                            color = EvaTextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        val steps = listOf(
                            "1. Open Settings -> About phone -> Tap 'Build number' 7 times to enable Developer options.",
                            "2. Go to Settings -> System -> Developer options -> Turn ON 'Wireless debugging' and connect to a Wi-Fi network.",
                            "3. In Developer options, tap 'Pair device with pairing code' to view the 6-digit code and port.",
                            "4. Open Shizuku -> Tap 'Pairing' -> Enter the 6-digit pairing code.",
                            "5. Return to Shizuku main screen and tap 'Start' to activate the server.",
                            "6. Return to EVA and tap 'Authorize EVA' to enable the bridge!"
                        )

                        for (step in steps) {
                            Text(
                                text = step,
                                color = EvaTextSecondary,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Common Troubleshooting Fixes:",
                            color = EvaYellowBright,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "• Server died after phone restart? Android stops user ADB daemons on boot. Simply re-open Shizuku and tap Start.\n" +
                                    "• Port unreachable? Android randomizes the Wireless Debugging port whenever Wi-Fi reconnects. Use the 'Auto-Detect' button above to find the active port.\n" +
                                    "• Permission denied? In the Shizuku app, check 'Authorized Applications' and ensure EVA is toggled ON.",
                            color = EvaTextTertiary,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
