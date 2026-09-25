package com.example.eva.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.example.R
import com.example.eva.overlay.EvaOverlayService
import com.example.eva.shizuku.ShizukuConnectionStatus
import com.example.eva.shizuku.WirelessSessionStatus
import com.example.eva.ui.EvaViewModel
import com.example.eva.ui.components.EvaGlassCard
import com.example.eva.ui.components.EvaNexusOrb
import com.example.eva.ui.components.EvaStatusChip
import com.example.eva.voice.VoicePersonality
import com.example.eva.voice.VoiceState
import com.example.ui.theme.*
import java.util.Calendar

data class QuickActionItem(
    val title: String,
    val icon: ImageVector,
    val command: String
)

@Composable
fun HomeScreen(
    viewModel: EvaViewModel,
    onNavigateToChat: () -> Unit,
    onNavigateToTools: () -> Unit,
    onNavigateToApps: () -> Unit,
    onNavigateToShizuku: () -> Unit,
    onNavigateToSchedule: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val voiceState by viewModel.voiceState.collectAsState()
    val rmsLevel by viewModel.rmsLevel.collectAsState()
    val isSpeaking by viewModel.isSpeaking.collectAsState()
    val speechText by viewModel.speechText.collectAsState()
    val sessionState by viewModel.sessionState.collectAsState()
    val settings by viewModel.settingsState.collectAsState()
    val shizukuInfo by viewModel.shizukuState.collectAsState()
    val wirelessSession by viewModel.wirelessSession.collectAsState()
    val statusBanner by viewModel.statusBanner.collectAsState()
    val context = LocalContext.current

    val currentHour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greeting = remember(currentHour) {
        when (currentHour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> "Good night"
        }
    }

    val quickActions = listOf(
        QuickActionItem("Overlay", Icons.Default.PictureInPicture, "open display overlay"),
        QuickActionItem("Flashlight", Icons.Default.FlashlightOn, "turn on flashlight"),
        QuickActionItem("Status", Icons.Default.BatteryChargingFull, "how is my phone"),
        QuickActionItem("Voice Memo", Icons.Default.Mic, "start voice recording"),
        QuickActionItem("Music", Icons.Default.MusicNote, "play my music"),
        QuickActionItem("Apps", Icons.Default.Apps, "open settings"),
        QuickActionItem("Shizuku", Icons.Default.Cable, "shizuku status"),
        QuickActionItem("News", Icons.Default.Feed, "what's today's news"),
        QuickActionItem("IP / Net", Icons.Default.Speed, "what's my IP")
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        EvaObsidian,
                        EvaCharcoal,
                        Color(0xFF0C0F14)
                    )
                )
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = greeting,
                    color = EvaTextSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal
                )
                Text(
                    text = "EVA",
                    color = EvaYellowPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
                Text(
                    text = "Electronic Virtual Assistant",
                    color = EvaTextTertiary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            IconButton(
                onClick = onNavigateToSettings,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(EvaSurfaceElevated)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = EvaYellowPrimary
                )
            }
        }

        // Live Status Badges
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            EvaStatusChip(
                text = "${settings.activeProvider.displayName} ● Active",
                icon = Icons.Default.SmartToy,
                color = EvaYellowPrimary,
                onClick = onNavigateToSettings
            )

            val (shizukuText, shizukuColor, shizukuIcon) = when {
                wirelessSession.sessionStatus == WirelessSessionStatus.ACTIVE_CONNECTED ->
                    Triple("Wireless Bridge ● Active (${wirelessSession.latencyMs}ms)", EvaCyanAccent, Icons.Default.WifiTethering)
                shizukuInfo.status == ShizukuConnectionStatus.AUTHORIZED_CONNECTED ->
                    Triple("Shizuku ● Connected", EvaSuccessGreen, Icons.Default.Cable)
                shizukuInfo.status == ShizukuConnectionStatus.PERMISSION_REQUIRED ->
                    Triple("Shizuku ● Auth Needed", EvaYellowBright, Icons.Default.Lock)
                shizukuInfo.status == ShizukuConnectionStatus.SERVER_NOT_RUNNING ->
                    Triple("Shizuku ● Inactive", EvaTextSecondary, Icons.Default.Cable)
                else ->
                    Triple("Shizuku Bridge", EvaTextTertiary, Icons.Default.Cable)
            }

            EvaStatusChip(
                text = shizukuText,
                icon = shizukuIcon,
                color = shizukuColor,
                onClick = onNavigateToShizuku
            )
        }

        // Status banner if present
        AnimatedVisibility(visible = statusBanner != null, enter = fadeIn(), exit = fadeOut()) {
            statusBanner?.let { banner ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = EvaSurfaceHighlight,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x33FFD54F)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = EvaYellowPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = banner,
                            color = EvaTextPrimary,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.clearBanner() }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = EvaTextSecondary, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Center Voice Nexus Orb
        EvaNexusOrb(
            voiceState = voiceState,
            rmsLevel = rmsLevel,
            isSpeaking = isSpeaking,
            onClick = {
                if (voiceState == VoiceState.LISTENING) {
                    viewModel.stopListening()
                } else if (isSpeaking) {
                    viewModel.stopSpeaking()
                } else {
                    viewModel.startListening()
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // State indicator label
        val stateLabel = when (voiceState) {
            VoiceState.LISTENING -> "Listening to your voice..."
            VoiceState.THINKING -> "Understanding context..."
            VoiceState.EXECUTING -> "Executing requested action..."
            VoiceState.SPEAKING -> "Speaking..."
            VoiceState.SLEEPING -> "Sleeping (Say 'Wake up')"
            VoiceState.ERROR -> "Voice input error"
            VoiceState.IDLE -> "How can I help you today?"
        }

        Text(
            text = stateLabel,
            color = if (voiceState == VoiceState.LISTENING) EvaCyanAccent else EvaTextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )

        // Live recognized speech text
        if (speechText.isNotBlank()) {
            Text(
                text = "\"$speechText\"",
                color = EvaYellowBright,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Voice Action Control Pill
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    if (voiceState == VoiceState.LISTENING) viewModel.stopListening() else viewModel.startListening()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (voiceState == VoiceState.LISTENING) EvaErrorRed else EvaYellowPrimary,
                    contentColor = Color(0xFF090A0E)
                ),
                shape = RoundedCornerShape(24.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Icon(
                    imageVector = if (voiceState == VoiceState.LISTENING) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (voiceState == VoiceState.LISTENING) "Stop Listening" else "Push to Talk",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            if (isSpeaking) {
                OutlinedButton(
                    onClick = { viewModel.stopSpeaking() },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = EvaYellowPrimary),
                    border = androidx.compose.foundation.BorderStroke(1.dp, EvaYellowPrimary),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Icon(Icons.Default.VolumeOff, contentDescription = "Stop", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Mute")
                }
            } else {
                OutlinedButton(
                    onClick = onNavigateToChat,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = EvaTextSecondary),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x33FFFFFF)),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Icon(Icons.Default.ChatBubbleOutline, contentDescription = "Chat", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Chat View")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Current Task & Active Session Card
        EvaGlassCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ACTIVE SESSION CONTEXT",
                        color = EvaYellowGold,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = sessionState.taskState.uppercase(),
                        color = if (sessionState.taskState == "active") EvaSuccessGreen else EvaTextTertiary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CheckCircleOutline,
                        contentDescription = null,
                        tint = EvaYellowPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Active App: ${sessionState.activeApp?.appName ?: "None"}",
                        color = EvaTextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (sessionState.activeTool != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Build,
                            contentDescription = null,
                            tint = EvaCyanAccent,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Last Tool: ${sessionState.activeTool} (${sessionState.lastAction ?: ""})",
                            color = EvaTextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }

                if (sessionState.lastEvaResponse != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "EVA: \"${sessionState.lastEvaResponse}\"",
                        color = EvaTextSecondary,
                        fontSize = 12.sp,
                        maxLines = 2
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Shizuku & Wireless Debugging Quick Card
        EvaGlassCard(
            modifier = Modifier.fillMaxWidth()
        ) {
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
                            text = "SHIZUKU & WIRELESS DEBUGGING",
                            color = EvaYellowGold,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }

                    val (badgeText, badgeColor) = when {
                        wirelessSession.sessionStatus == WirelessSessionStatus.ACTIVE_CONNECTED -> "BRIDGE ACTIVE" to EvaCyanAccent
                        shizukuInfo.status == ShizukuConnectionStatus.AUTHORIZED_CONNECTED -> "CONNECTED" to EvaSuccessGreen
                        shizukuInfo.status == ShizukuConnectionStatus.PERMISSION_REQUIRED -> "AUTH NEEDED" to EvaYellowBright
                        shizukuInfo.status == ShizukuConnectionStatus.SERVER_NOT_RUNNING -> "STOPPED" to EvaErrorRed
                        else -> "NOT FOUND" to Color(0xFF64748B)
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = badgeColor.copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, badgeColor.copy(alpha = 0.6f))
                    ) {
                        Text(
                            text = badgeText,
                            color = badgeColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                val subtitle = when {
                    wirelessSession.sessionStatus == WirelessSessionStatus.ACTIVE_CONNECTED ->
                        "Active wireless bridge via 127.0.0.1:${wirelessSession.activePort ?: 5555} • Latency: ${wirelessSession.latencyMs}ms"
                    shizukuInfo.status == ShizukuConnectionStatus.AUTHORIZED_CONNECTED -> {
                        val mode = if (shizukuInfo.serverUid == 0) "Root" else "Wireless Debugging Shell (UID 2000)"
                        "Shizuku Connected: v${shizukuInfo.serverVersion} running in $mode mode. Ready to bridge ADB commands."
                    }
                    shizukuInfo.status == ShizukuConnectionStatus.PERMISSION_REQUIRED ->
                        "Shizuku service is running. Tap to grant authorization to enable device diagnostics."
                    shizukuInfo.status == ShizukuConnectionStatus.SERVER_NOT_RUNNING ->
                        "Shizuku server not running. Start via Android 11+ Wireless Debugging without PC."
                    else ->
                        "Shizuku allows elevated shell control without root via Android Wireless Debugging."
                }

                Text(
                    text = subtitle,
                    color = EvaTextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = onNavigateToShizuku,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = EvaYellowPrimary),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x33FFD54F)),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Launch,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (wirelessSession.sessionStatus == WirelessSessionStatus.ACTIVE_CONNECTED) "Manage Bridge" else "Bridge Console",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Floating Display Overlay Card with Premium White Logo
        EvaGlassCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFF161B22),
                            border = androidx.compose.foundation.BorderStroke(2.dp, Color.White),
                            modifier = Modifier.size(28.dp)
                        ) {
                            androidx.compose.foundation.Image(
                                painter = painterResource(id = R.drawable.ic_eva_white_logo),
                                contentDescription = "EVA Premium White Logo",
                                modifier = Modifier.padding(4.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "FLOATING DISPLAY OVERLAY",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }

                    val overlayGranted = EvaOverlayService.isOverlayPermissionGranted(context)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (overlayGranted) EvaSuccessGreen.copy(alpha = 0.15f) else EvaYellowBright.copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (overlayGranted) EvaSuccessGreen else EvaYellowBright)
                    ) {
                        Text(
                            text = if (overlayGranted) "READY" else "PERMISSION NEEDED",
                            color = if (overlayGranted) EvaSuccessGreen else EvaYellowBright,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Floating bubble with premium white logo. Stays on screen over any app (clicking anywhere will not vanish it). Speak commands like 'Open Adguard', 'Close ads', and 'Turn the protection on'. Shizuku powers full screen analysis, click, scroll, and slide access.",
                    color = EvaTextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (EvaOverlayService.isOverlayPermissionGranted(context)) {
                                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                                    addCategory(Intent.CATEGORY_HOME)
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(homeIntent)
                                EvaOverlayService.startOverlay(context)
                            } else {
                                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color(0xFF090A0E)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PictureInPicture,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Open Display Overlay",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            viewModel.processCommand("close ads")
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = EvaYellowPrimary),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x33FFD54F)),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "Close Ads",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Quick Action Chips Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "QUICK ACTIONS",
                color = EvaTextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Text(
                text = "View All Tools",
                color = EvaYellowPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable(onClick = onNavigateToTools)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Horizontal Quick Action Carousel
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(quickActions) { item ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = EvaSurfaceElevated,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x22FFD54F)),
                    modifier = Modifier.clickable {
                        viewModel.processCommand(item.command)
                    }
                ) {
                    Column(
                        modifier = Modifier
                            .width(88.dp)
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.title,
                            tint = EvaYellowPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = item.title,
                            color = EvaTextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Sample Voice Prompts Guide
        EvaGlassCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "VOICE COMMAND EXAMPLES",
                    color = EvaYellowGold,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                val sampleCommands = listOf(
                    "\"Open YouTube and search for Class 9 Physics\"",
                    "\"Open AdGuard\" -> then \"Turn it on\"",
                    "\"What's my battery and device status?\"",
                    "\"Turn on flashlight\"",
                    "\"Use OmniRoute\" or \"Switch to OpenRouter\""
                )

                for (cmd in sampleCommands) {
                    Text(
                        text = "• $cmd",
                        color = EvaTextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val clean = cmd.replace("\"", "").substringBefore(" ->")
                                viewModel.processCommand(clean)
                            }
                            .padding(vertical = 3.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(30.dp))
    }
}
