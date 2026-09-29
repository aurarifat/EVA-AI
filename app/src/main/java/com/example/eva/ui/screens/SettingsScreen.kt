package com.example.eva.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.eva.automation.ActiveAutomationBackendType
import com.example.eva.data.prefs.AiProviderType
import com.example.eva.data.prefs.SecureKeyStore
import com.example.eva.ui.EvaViewModel
import com.example.eva.ui.components.EvaGlassCard
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: EvaViewModel,
    onBack: () -> Unit,
    onNavigateToPrivacy: () -> Unit,
    onNavigateToShizuku: () -> Unit
) {
    val context = LocalContext.current
    val settings by viewModel.settingsState.collectAsState()
    val testResult by viewModel.providerTestResult.collectAsState()

    var omniUrl by remember(settings.omniRouteUrl) { mutableStateOf(settings.omniRouteUrl) }
    var omniModel by remember(settings.omniRouteModel) { mutableStateOf(settings.omniRouteModel) }
    var omniKey by remember { mutableStateOf("") }

    var openRouterUrl by remember(settings.openRouterUrl) { mutableStateOf(settings.openRouterUrl) }
    var openRouterModel by remember(settings.openRouterModel) { mutableStateOf(settings.openRouterModel) }
    var openRouterKey by remember { mutableStateOf("") }

    var geminiModel by remember(settings.geminiModel) { mutableStateOf(settings.geminiModel) }
    var geminiKey by remember { mutableStateOf("") }

    var voiceSpeed by remember(settings.voiceSpeed) { mutableFloatStateOf(settings.voiceSpeed) }
    var voicePitch by remember(settings.voicePitch) { mutableFloatStateOf(settings.voicePitch) }
    var voiceLang by remember(settings.voiceLanguage) { mutableStateOf(settings.voiceLanguage) }
    var autoSpeak by remember(settings.autoSpeak) { mutableStateOf(settings.autoSpeak) }

    var debugTapCrosshair by remember(settings.debugTapCrosshair) { mutableStateOf(settings.debugTapCrosshair) }
    var isAgentMode by remember(settings.isAgentMode) { mutableStateOf(settings.isAgentMode) }
    var temperature by remember(settings.temperature) { mutableFloatStateOf(settings.temperature) }
    var maxSteps by remember(settings.maxSteps) { mutableIntStateOf(settings.maxSteps) }
    var disableMaxSteps by remember(settings.disableMaxSteps) { mutableStateOf(settings.disableMaxSteps) }
    var useScreenCompression by remember(settings.useScreenCompression) { mutableStateOf(settings.useScreenCompression) }
    var sendSystemPrompt by remember(settings.sendSystemPrompt) { mutableStateOf(settings.sendSystemPrompt) }
    val taskTraces by viewModel.taskTraces.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings & AI Engine", color = EvaTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = EvaTextPrimary)
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
            // Active Provider Selector
            EvaGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("ACTIVE AI BACKEND PROVIDER", color = EvaYellowGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(10.dp))

                    AiProviderType.values().forEach { type ->
                        val isSelected = settings.activeProvider == type
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) EvaYellowPrimary else EvaSurfaceElevated,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clickable { viewModel.switchProvider(type) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { viewModel.switchProvider(type) },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = Color(0xFF090A0E),
                                        unselectedColor = EvaTextSecondary
                                    )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = type.displayName,
                                    color = if (isSelected) Color(0xFF090A0E) else EvaTextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = { viewModel.testProvider(settings.activeProvider) },
                        colors = ButtonDefaults.buttonColors(containerColor = EvaSurfaceHighlight, contentColor = EvaYellowPrimary),
                        border = androidx.compose.foundation.BorderStroke(1.dp, EvaYellowPrimary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Test Connection (${settings.activeProvider.displayName})")
                    }

                    if (testResult != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = testResult?.message ?: "",
                            color = if (testResult?.isSuccess == true) EvaSuccessGreen else EvaErrorRed,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // OmniRoute Settings Card
            EvaGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("OMNIROUTE CONFIGURATION", color = EvaYellowGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = omniUrl,
                        onValueChange = { omniUrl = it },
                        label = { Text("Base URL (e.g. http://10.0.2.2:20128/v1)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = omniModel,
                        onValueChange = { omniModel = it },
                        label = { Text("Model identifier") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = omniKey,
                        onValueChange = { omniKey = it },
                        label = { Text("API Key (Current: ${SecureKeyStore.maskKey(viewModel.keyStore.getKey(AiProviderType.OMNI_ROUTE))})") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            viewModel.saveOmniRouteConfig(omniUrl, omniModel, omniKey)
                            omniKey = ""
                            Toast.makeText(context, "Saved OmniRoute settings", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = EvaYellowPrimary, contentColor = Color(0xFF090A0E))
                    ) {
                        Text("Save OmniRoute")
                    }
                }
            }

            // OpenRouter Settings Card
            EvaGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("OPENROUTER CONFIGURATION", color = EvaYellowGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = openRouterUrl,
                        onValueChange = { openRouterUrl = it },
                        label = { Text("Base URL") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = openRouterModel,
                        onValueChange = { openRouterModel = it },
                        label = { Text("Model (e.g. google/gemini-2.5-flash)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = openRouterKey,
                        onValueChange = { openRouterKey = it },
                        label = { Text("API Key (Current: ${SecureKeyStore.maskKey(viewModel.keyStore.getKey(AiProviderType.OPEN_ROUTER))})") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            viewModel.saveOpenRouterConfig(openRouterUrl, openRouterModel, openRouterKey)
                            openRouterKey = ""
                            Toast.makeText(context, "Saved OpenRouter settings", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = EvaYellowPrimary, contentColor = Color(0xFF090A0E))
                    ) {
                        Text("Save OpenRouter")
                    }
                }
            }

            // Gemini Settings Card
            EvaGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("GOOGLE GEMINI CONFIGURATION", color = EvaYellowGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = geminiModel,
                        onValueChange = { geminiModel = it },
                        label = { Text("Gemini Model (e.g. gemini-2.5-flash)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = geminiKey,
                        onValueChange = { geminiKey = it },
                        label = { Text("Gemini API Key (Current: ${SecureKeyStore.maskKey(viewModel.keyStore.getKey(AiProviderType.GEMINI))})") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            viewModel.saveGeminiConfig(geminiModel, geminiKey)
                            geminiKey = ""
                            Toast.makeText(context, "Saved Gemini settings", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = EvaYellowPrimary, contentColor = Color(0xFF090A0E))
                    ) {
                        Text("Save Gemini")
                    }
                }
            }

            // Voice Engine Settings
            EvaGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("VOICE & SPEECH SYNTHESIS", color = EvaYellowGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))

                    Text("Speech Rate: ${"%.1f".format(voiceSpeed)}x", color = EvaTextPrimary, fontSize = 13.sp)
                    Slider(
                        value = voiceSpeed,
                        onValueChange = { voiceSpeed = it },
                        valueRange = 0.5f..1.8f,
                        colors = SliderDefaults.colors(thumbColor = EvaYellowPrimary, activeTrackColor = EvaYellowPrimary)
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Pitch Tone: ${"%.2f".format(voicePitch)} (Warm & gentle)", color = EvaTextPrimary, fontSize = 13.sp)
                    Slider(
                        value = voicePitch,
                        onValueChange = { voicePitch = it },
                        valueRange = 0.8f..1.4f,
                        colors = SliderDefaults.colors(thumbColor = EvaYellowPrimary, activeTrackColor = EvaYellowPrimary)
                    )

                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Auto-speak responses", color = EvaTextPrimary, fontSize = 13.sp)
                        Switch(
                            checked = autoSpeak,
                            onCheckedChange = { autoSpeak = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = EvaYellowPrimary, checkedTrackColor = Color(0xFF2A2312))
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            viewModel.updateVoiceConfig(voiceSpeed, voicePitch, voiceLang, autoSpeak)
                            Toast.makeText(context, "Updated voice settings", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = EvaYellowPrimary, contentColor = Color(0xFF090A0E))
                    ) {
                        Text("Save Voice Settings")
                    }
                }
            }

            // Automation Backend & Gesture System Card
            EvaGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val activeBackend = viewModel.getActiveBackendType()
                    val isA11yActive = viewModel.isAccessibilityActive()
                    val isShizukuActive = viewModel.isShizukuActive()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("AUTOMATION ENGINE & GESTURE BACKEND", color = EvaYellowGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = when (activeBackend) {
                                ActiveAutomationBackendType.ACCESSIBILITY -> EvaSuccessGreen.copy(alpha = 0.2f)
                                ActiveAutomationBackendType.SHIZUKU -> EvaYellowPrimary.copy(alpha = 0.2f)
                                ActiveAutomationBackendType.NONE -> EvaErrorRed.copy(alpha = 0.2f)
                            }
                        ) {
                            Text(
                                text = when (activeBackend) {
                                    ActiveAutomationBackendType.ACCESSIBILITY -> "Active: Accessibility"
                                    ActiveAutomationBackendType.SHIZUKU -> "Active: Shizuku (Fallback)"
                                    ActiveAutomationBackendType.NONE -> "Active: None (Disabled)"
                                },
                                color = when (activeBackend) {
                                    ActiveAutomationBackendType.ACCESSIBILITY -> EvaSuccessGreen
                                    ActiveAutomationBackendType.SHIZUKU -> EvaYellowPrimary
                                    ActiveAutomationBackendType.NONE -> EvaErrorRed
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Accessibility Service Sub-card
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = EvaSurfaceElevated,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Accessibility, contentDescription = null, tint = EvaYellowPrimary, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("EVA Accessibility Service", color = EvaTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                                Text(
                                    text = if (isA11yActive) "Running" else "Disabled",
                                    color = if (isA11yActive) EvaSuccessGreen else EvaErrorRed,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Delivers real-time screen node analysis, coordinate-safe gesture taps, text typing & password protection without requiring ADB.",
                                color = EvaTextSecondary,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    context.startActivity(intent)
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = EvaYellowPrimary),
                                border = androidx.compose.foundation.BorderStroke(1.dp, EvaYellowPrimary.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (isA11yActive) "Accessibility Settings" else "Enable in Android Settings", fontSize = 12.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Shizuku Fallback Sub-card
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = EvaSurfaceElevated,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Cable, contentDescription = null, tint = EvaCyanAccent, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Shizuku ADB Shell (Fallback)", color = EvaTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                                Text(
                                    text = if (isShizukuActive) "Authorized" else "Unauthorized",
                                    color = if (isShizukuActive) EvaSuccessGreen else EvaTextTertiary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Secondary fallback engine for executing shell input gestures when accessibility is unavailable.",
                                color = EvaTextSecondary,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = onNavigateToShizuku,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = EvaCyanAccent),
                                border = androidx.compose.foundation.BorderStroke(1.dp, EvaCyanAccent.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Sensors, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Shizuku Status & Wireless Pairing", fontSize = 12.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Split-screen unsupported notice (Required)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0x22F5A623),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x55F5A623)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFF5A623), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Multi-Window / Split-Screen Unsupported: Automation coordinates and screen bounds dynamically target full-screen orientation. Split-screen and multi-window modes are unsupported.",
                                color = EvaTextSecondary,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Tap Crosshair Debug Mode Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Debug Tap Crosshair Overlay", color = EvaTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("Draws a brief visual marker at target (x, y) coordinates before tapping or swiping", color = EvaTextSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = debugTapCrosshair,
                            onCheckedChange = {
                                debugTapCrosshair = it
                                viewModel.setDebugTapCrosshair(it)
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = EvaYellowPrimary, checkedTrackColor = Color(0xFF2A2312))
                        )
                    }
                }
            }

            // Agent Mode & Core AI Parameters Card
            EvaGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("CORE AI PARAMETERS & AGENT MODE", color = EvaYellowGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(10.dp))

                    // Agent Mode Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Agent Mode (Autonomous Execution)", color = EvaTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("Permits EVA to execute tools and multi-step plans autonomously", color = EvaTextSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = isAgentMode,
                            onCheckedChange = {
                                isAgentMode = it
                                viewModel.updateAgentMode(it)
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = EvaYellowPrimary, checkedTrackColor = Color(0xFF2A2312))
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Temperature
                    Text("Sampling Temperature: ${"%.2f".format(temperature)}", color = EvaTextPrimary, fontSize = 13.sp)
                    Slider(
                        value = temperature,
                        onValueChange = { temperature = it },
                        valueRange = 0.0f..1.5f,
                        colors = SliderDefaults.colors(thumbColor = EvaYellowPrimary, activeTrackColor = EvaYellowPrimary)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Max Steps
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (disableMaxSteps) "Max Steps: Unlimited" else "Max Steps: $maxSteps",
                            color = EvaTextPrimary,
                            fontSize = 13.sp
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = disableMaxSteps,
                                onCheckedChange = { disableMaxSteps = it },
                                colors = CheckboxDefaults.colors(checkedColor = EvaYellowPrimary)
                            )
                            Text("Unlimited", color = EvaTextSecondary, fontSize = 12.sp)
                        }
                    }
                    if (!disableMaxSteps) {
                        Slider(
                            value = maxSteps.toFloat(),
                            onValueChange = { maxSteps = it.toInt() },
                            valueRange = 1f..25f,
                            steps = 23,
                            colors = SliderDefaults.colors(thumbColor = EvaYellowPrimary, activeTrackColor = EvaYellowPrimary)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Screen Compression Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("UI Hierarchy Compression", color = EvaTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("Deduplicates empty container nodes to save model tokens", color = EvaTextSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = useScreenCompression,
                            onCheckedChange = { useScreenCompression = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = EvaYellowPrimary, checkedTrackColor = Color(0xFF2A2312))
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // System Prompt Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Include System Tool Schema", color = EvaTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("Appends full Android tool definitions to every AI prompt", color = EvaTextSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = sendSystemPrompt,
                            onCheckedChange = { sendSystemPrompt = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = EvaYellowPrimary, checkedTrackColor = Color(0xFF2A2312))
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            viewModel.updateExecutionParams(
                                temperature = temperature,
                                maxTokens = 2048,
                                maxSteps = maxSteps,
                                disableMaxSteps = disableMaxSteps,
                                screenCompression = useScreenCompression,
                                sendSystemPrompt = sendSystemPrompt
                            )
                            Toast.makeText(context, "Saved execution parameters", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = EvaYellowPrimary, contentColor = Color(0xFF090A0E)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save Execution Parameters")
                    }
                }
            }

            // Task Execution History Card
            EvaGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("AGENT TASK EXECUTION HISTORY", color = EvaYellowGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        if (taskTraces.isNotEmpty()) {
                            TextButton(onClick = { viewModel.clearTaskTraces() }) {
                                Text("Clear", color = EvaTextTertiary, fontSize = 11.sp)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    if (taskTraces.isEmpty()) {
                        Text(
                            text = "No autonomous tasks recorded yet. Say \"Open YouTube and search for Physics\" to view execution traces.",
                            color = EvaTextTertiary,
                            fontSize = 12.sp
                        )
                    } else {
                        taskTraces.take(5).forEach { trace ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = EvaSurfaceElevated,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (trace.status == "COMPLETED") Color(0x334CAF50) else Color(0x33FF5252)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = trace.userGoal,
                                            color = EvaTextPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = if (trace.status == "COMPLETED") Color(0x224CAF50) else Color(0x22FF5252)
                                        ) {
                                            Text(
                                                text = trace.status,
                                                color = if (trace.status == "COMPLETED") EvaSuccessGreen else Color(0xFFFF5252),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "${trace.totalSteps} steps • ${trace.finalSummary}",
                                        color = EvaTextSecondary,
                                        fontSize = 11.sp,
                                        maxLines = 2
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Privacy & Advanced Shortcuts
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = EvaSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x22FFFFFF)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onNavigateToPrivacy)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = EvaCyanAccent)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Privacy Center & Permissions", color = EvaTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("Inspect hardware sensor permissions & security guarantees", color = EvaTextSecondary, fontSize = 12.sp)
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = EvaTextTertiary)
                }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = EvaSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x22FFFFFF)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onNavigateToShizuku)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Cable, contentDescription = null, tint = EvaYellowPrimary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Shizuku & Wireless Debugging", color = EvaTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("Service status, pairing port test & authorized ADB whitelist", color = EvaTextSecondary, fontSize = 12.sp)
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = EvaTextTertiary)
                }
            }

            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}
