package com.example.eva.overlay

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.eva.agent.EvaAgentMode
import kotlin.math.sqrt

// High-end Obsidian & Gold Theme Palette
private val BubbleObsidian = Color(0xF50B0F17)
private val BubbleCardSurface = Color(0xF8121724)
private val GoldAccent = Color(0xFFF6D860)
private val GoldBorder = Color(0xFFD4AF37)
private val CyanListening = Color(0xFF00E5FF)
private val EmeraldSpeaking = Color(0xFF00E676)
private val BroadcastRed = Color(0xFFFF5252)
private val SubtextGray = Color(0xFF8B949E)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EvaBubbleOverlayContent(
    state: BubbleOverlayUiState,
    onDragDelta: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit = {},
    onBubbleClick: () -> Unit,
    onStartVoice: () -> Unit,
    onStopVoice: () -> Unit,
    onToggleExpand: () -> Unit,
    onGoHome: () -> Unit,
    onQuickAction: (String) -> Unit,
    onOpenApp: () -> Unit,
    onCloseOverlay: () -> Unit,
    onRequestInputFocus: (Boolean) -> Unit = {},
    onSwitchAgentMode: (EvaAgentMode) -> Unit = {},
    onToggleBroadcast: () -> Unit = {},
    onPauseBroadcast: () -> Unit = {},
    onPauseSession: () -> Unit = {},
    onConfirmAction: () -> Unit = {},
    onCancelAction: () -> Unit = {},
    onClearHistory: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "bubble_animations")
    val focusManager = LocalFocusManager.current
    var typedCommand by remember { mutableStateOf("") }
    var showPreviewThumbnail by remember { mutableStateOf(false) }

    // Dynamic pulse for bubble
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = when {
            state.isWakeWordHighlight -> 1.20f
            state.mode == BubbleMode.LISTENING -> 1.14f
            state.mode == BubbleMode.SPEAKING -> 1.08f
            else -> 1.02f
        },
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when {
                    state.isWakeWordHighlight -> 350
                    state.mode == BubbleMode.LISTENING -> 500
                    state.mode == BubbleMode.SPEAKING -> 800
                    else -> 2000
                },
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = when (state.mode) {
            BubbleMode.LISTENING -> 0.95f
            BubbleMode.SPEAKING -> 0.85f
            BubbleMode.IDLE -> 0.50f
        },
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )

    Column(
        modifier = modifier
            .padding(4.dp)
            .widthIn(max = 280.dp)
    ) {
        // Floating Head Row (Bubble + Live indicators)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(2.dp)
        ) {
            val audioIntensityBoost = if (state.mode == BubbleMode.LISTENING) {
                (state.rmsLevel.coerceIn(0f, 1f) * 0.22f)
            } else 0f

            // Main Compact Floating Circular Orb (56.dp) with edge snap & dynamic audio reactivity
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(56.dp)
                    .scale(pulseScale + audioIntensityBoost)
                    .shadow(14.dp, CircleShape)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var totalDragDistance = 0f
                            var isDragging = false
                            val dragThreshold = 14f

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break

                                if (!change.pressed) {
                                    if (isDragging) {
                                        onDragEnd()
                                    } else {
                                        onBubbleClick()
                                    }
                                    break
                                }

                                val drag = change.positionChange()
                                val dist = sqrt(drag.x * drag.x + drag.y * drag.y)
                                totalDragDistance += dist

                                if (!isDragging && totalDragDistance > dragThreshold) {
                                    isDragging = true
                                }

                                if (isDragging) {
                                    change.consume()
                                    onDragDelta(drag.x, drag.y)
                                }
                            }
                        }
                    }
            ) {
                // Concentric Audio Pulse Rings when Listening
                if (state.mode == BubbleMode.LISTENING) {
                    val pulseRingScale by infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = 1.40f + (state.rmsLevel.coerceIn(0f, 1f) * 0.35f),
                        animationSpec = infiniteRepeatable(tween(750, easing = FastOutSlowInEasing), RepeatMode.Restart),
                        label = "pulse_ring"
                    )
                    val pulseRingAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.85f,
                        targetValue = 0f,
                        animationSpec = infiniteRepeatable(tween(750, easing = LinearEasing), RepeatMode.Restart),
                        label = "pulse_alpha"
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .scale(pulseRingScale)
                            .clip(CircleShape)
                            .border(
                                width = (1.5.dp + (state.rmsLevel.coerceIn(0f, 1f) * 2f).dp),
                                color = CyanListening.copy(alpha = pulseRingAlpha),
                                shape = CircleShape
                            )
                    )
                }

                // Outer Glowing Halo
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(
                            when {
                                state.isScreenBroadcasting -> BroadcastRed.copy(alpha = glowAlpha * 0.45f)
                                state.isWakeWordHighlight -> GoldAccent.copy(alpha = 0.90f)
                                state.mode == BubbleMode.LISTENING -> CyanListening.copy(alpha = (glowAlpha * 0.5f) + (state.rmsLevel.coerceIn(0f, 1f) * 0.4f))
                                state.mode == BubbleMode.SPEAKING -> EmeraldSpeaking.copy(alpha = glowAlpha * 0.40f)
                                else -> GoldAccent.copy(alpha = glowAlpha * 0.25f)
                            }
                        )
                )

                // High-End Circular Frame with Special EVA Logo
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.sweepGradient(
                                listOf(
                                    Color(0xFF201A09),
                                    GoldBorder,
                                    Color(0xFF382E0B),
                                    GoldAccent,
                                    Color(0xFF141108)
                                )
                            )
                        )
                        .border(1.5.dp, if (state.mode == BubbleMode.LISTENING) CyanListening else GoldAccent, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.eva_gold_logo_1790337115749),
                        contentDescription = "EVA Special Logo",
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                }

                // Live Screen Broadcast Recording Badge (Red blinking dot)
                if (state.isScreenBroadcasting) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(Color.Black)
                            .padding(2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(if (state.isBroadcastPaused) GoldAccent else BroadcastRed)
                        )
                    }
                }
            }

            // Real-Time Voice Activity / Speaking Pill (appears smoothly beside bubble)
            AnimatedVisibility(
                visible = state.mode != BubbleMode.IDLE,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = BubbleObsidian,
                    border = androidx.compose.foundation.BorderStroke(
                        width = 1.dp,
                        color = when (state.mode) {
                            BubbleMode.LISTENING -> CyanListening.copy(alpha = 0.85f)
                            BubbleMode.SPEAKING -> EmeraldSpeaking.copy(alpha = 0.85f)
                            else -> GoldAccent.copy(alpha = 0.5f)
                        }
                    ),
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .clickable {
                            if (state.mode == BubbleMode.LISTENING) onStopVoice() else onBubbleClick()
                        }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        if (state.mode == BubbleMode.LISTENING) {
                            ListeningAudioWave(rmsLevel = state.rmsLevel)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (state.recognizedText.isNotBlank()) "\"${state.recognizedText}\"" else "Hearing you...",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else if (state.mode == BubbleMode.SPEAKING) {
                            SpeakingEqualizerBars()
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (state.spokenText.isNotBlank()) state.spokenText else "EVA Speaking...",
                                color = EmeraldSpeaking,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // Expandable Quick Action Assistant Panel
        AnimatedVisibility(
            visible = state.isExpanded,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically()
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = BubbleCardSurface,
                shadowElevation = 12.dp,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    Brush.verticalGradient(listOf(GoldAccent.copy(alpha = 0.7f), Color(0x3300E5FF)))
                ),
                modifier = Modifier
                    .padding(top = 6.dp)
                    .widthIn(max = 280.dp)
            ) {
                Column(
                    modifier = Modifier.padding(10.dp)
                ) {
                    // Header: Logo, Title, Active Provider, and Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                painter = painterResource(id = R.drawable.eva_gold_logo_1790337115749),
                                contentDescription = "EVA Special Logo",
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .border(1.dp, GoldAccent, CircleShape),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(modifier = Modifier.width(7.dp))
                            Column {
                                Text(
                                    text = "EVA ASSISTANT",
                                    color = GoldAccent,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.6.sp
                                )
                                Text(
                                    text = "${state.agentMode.shortName} • ${state.activeProvider.displayName}",
                                    color = SubtextGray,
                                    fontSize = 8.5.sp
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Pause / Resume Session button
                            IconButton(
                                onClick = onPauseSession,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = if (state.isSessionPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = "Pause Session",
                                    tint = if (state.isSessionPaused) GoldAccent else SubtextGray,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            // Minimize button
                            IconButton(
                                onClick = onToggleExpand,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowBack,
                                    contentDescription = "Minimize Panel",
                                    tint = SubtextGray,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            // Close overlay button
                            IconButton(
                                onClick = onCloseOverlay,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close Overlay",
                                    tint = SubtextGray,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Agent Mode Selector Row (Horizontally scrollable pills)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        EvaAgentMode.values().forEach { mode ->
                            val isSelected = state.agentMode == mode
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) GoldAccent else Color(0x22FFFFFF),
                                modifier = Modifier.clickable { onSwitchAgentMode(mode) }
                            ) {
                                Text(
                                    text = mode.shortName,
                                    color = if (isSelected) Color(0xFF0B0F17) else Color.White,
                                    fontSize = 9.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Live Screen Broadcast Control Bar
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (state.isScreenBroadcasting) BroadcastRed.copy(alpha = 0.15f) else Color(0x22FFFFFF),
                        border = androidx.compose.foundation.BorderStroke(
                            0.5.dp,
                            if (state.isScreenBroadcasting) BroadcastRed else Color(0x33FFFFFF)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (state.isScreenBroadcasting) Icons.Default.FiberManualRecord else Icons.Default.ScreenShare,
                                    contentDescription = null,
                                    tint = if (state.isScreenBroadcasting) BroadcastRed else GoldAccent,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = when {
                                        !state.isScreenBroadcasting -> "Live Screen Broadcast"
                                        state.isBroadcastPaused -> "Broadcast Paused"
                                        else -> "Live Screen Active (540p)"
                                    },
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (state.isScreenBroadcasting) {
                                    // Pause / Resume Broadcast
                                    IconButton(
                                        onClick = onPauseBroadcast,
                                        modifier = Modifier.size(22.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (state.isBroadcastPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                            contentDescription = "Pause Broadcast",
                                            tint = GoldAccent,
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }

                                    // Toggle Thumbnail preview
                                    if (state.latestThumbnail != null) {
                                        Text(
                                            text = if (showPreviewThumbnail) "Hide" else "View",
                                            color = CyanListening,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier
                                                .clickable { showPreviewThumbnail = !showPreviewThumbnail }
                                                .padding(horizontal = 4.dp)
                                        )
                                    }

                                    // Stop Broadcast
                                    IconButton(
                                        onClick = onToggleBroadcast,
                                        modifier = Modifier.size(22.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.StopScreenShare,
                                            contentDescription = "Stop Broadcast",
                                            tint = BroadcastRed,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                } else {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = GoldAccent,
                                        modifier = Modifier.clickable { onToggleBroadcast() }
                                    ) {
                                        Text(
                                            text = "Start",
                                            color = Color(0xFF090A0E),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Optional Live Thumbnail Preview Card
                    if (state.isScreenBroadcasting && showPreviewThumbnail && state.latestThumbnail != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, GoldAccent.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        ) {
                            Image(
                                bitmap = state.latestThumbnail.asImageBitmap(),
                                contentDescription = "Live Screen Thumbnail",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Sensitive Action Confirmation Banner
                    if (state.pendingConfirmation != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF331B1B),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF5252)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(
                                    text = "Confirm Sensitive Action:",
                                    color = Color(0xFFFF8A80),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = state.pendingConfirmation,
                                    color = Color.White,
                                    fontSize = 9.5.sp,
                                    maxLines = 2
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Button(
                                        onClick = onConfirmAction,
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252)),
                                        modifier = Modifier.height(26.dp)
                                    ) {
                                        Text("Confirm", fontSize = 9.sp, color = Color.White)
                                    }
                                    Button(
                                        onClick = onCancelAction,
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FFFFFF)),
                                        modifier = Modifier.height(26.dp)
                                    ) {
                                        Text("Cancel", fontSize = 9.sp, color = Color.White)
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    // Live Task & Status Banner
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0x33000000),
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, Color(0x33FFFFFF)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = state.currentTask ?: state.statusText,
                            color = when (state.mode) {
                                BubbleMode.LISTENING -> CyanListening
                                BubbleMode.SPEAKING -> EmeraldSpeaking
                                else -> Color(0xFFE2E8F0)
                            },
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                        )
                    }

                    // Dynamic Audio Waveform Visualizer Banner (Reacts when Listening or Speaking)
                    if (state.mode == BubbleMode.LISTENING || state.mode == BubbleMode.SPEAKING) {
                        Spacer(modifier = Modifier.height(6.dp))
                        DynamicVoiceWaveformCard(
                            mode = state.mode,
                            rmsLevel = state.rmsLevel,
                            recognizedText = state.recognizedText,
                            spokenText = state.spokenText
                        )
                    }

                    // Persistent Conversation History (compact, scrollable)
                    if (state.conversationHistory.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        val listState = rememberLazyListState()
                        LaunchedEffect(state.conversationHistory.size) {
                            listState.animateScrollToItem(state.conversationHistory.size - 1)
                        }

                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 110.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0x22000000))
                                .padding(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(state.conversationHistory) { item ->
                                val isUser = item.role == "user"
                                val isSystem = item.role == "system"
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = when {
                                            isUser -> GoldAccent.copy(alpha = 0.2f)
                                            isSystem -> Color(0x2200E5FF)
                                            else -> Color(0x33FFFFFF)
                                        }
                                    ) {
                                        Text(
                                            text = item.content,
                                            color = when {
                                                isUser -> GoldAccent
                                                isSystem -> CyanListening
                                                else -> Color.White
                                            },
                                            fontSize = 9.5.sp,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Primary Push-to-Talk Voice Mic Button
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (state.mode == BubbleMode.LISTENING) CyanListening.copy(alpha = 0.25f) else Color(0x2200E5FF),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (state.mode == BubbleMode.LISTENING) CyanListening else CyanListening.copy(alpha = 0.6f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (state.mode == BubbleMode.LISTENING) {
                                    onStopVoice()
                                } else {
                                    onStartVoice()
                                }
                            }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(vertical = 7.dp, horizontal = 8.dp)
                        ) {
                            if (state.mode == BubbleMode.LISTENING) {
                                ListeningAudioWave(rmsLevel = state.rmsLevel)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Listening... Tap to Stop",
                                    color = CyanListening,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = "Tap to speak",
                                    tint = CyanListening,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Tap to Speak Voice Command",
                                    color = Color.White,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Quick Command Text Input Field
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = typedCommand,
                            onValueChange = { typedCommand = it },
                            placeholder = { Text("Command (e.g. open YouTube)...", fontSize = 9.sp, color = SubtextGray) },
                            singleLine = true,
                            maxLines = 1,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    if (typedCommand.isNotBlank()) {
                                        val cmd = typedCommand
                                        typedCommand = ""
                                        focusManager.clearFocus()
                                        onRequestInputFocus(false)
                                        onQuickAction(cmd)
                                    }
                                }
                            ),
                            colors = TextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = GoldAccent,
                                focusedIndicatorColor = GoldAccent,
                                unfocusedIndicatorColor = Color(0x44FFFFFF),
                                focusedContainerColor = Color(0x33000000),
                                unfocusedContainerColor = Color(0x33000000)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .onFocusChanged { focusState ->
                                    onRequestInputFocus(focusState.isFocused)
                                }
                        )

                        Spacer(modifier = Modifier.width(4.dp))

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = GoldAccent,
                            modifier = Modifier
                                .size(36.dp)
                                .clickable {
                                    if (typedCommand.isNotBlank()) {
                                        val cmd = typedCommand
                                        typedCommand = ""
                                        focusManager.clearFocus()
                                        onRequestInputFocus(false)
                                        onQuickAction(cmd)
                                    }
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Send,
                                    contentDescription = "Send",
                                    tint = Color(0xFF090A0E),
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Voice Quick Action Chips (Voice-First, No flashlight, No protection clutter)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        QuickActionChip("Home", "home", onQuickAction)
                        QuickActionChip("Battery", "what is my battery", onQuickAction)
                        QuickActionChip("Time", "what time is it", onQuickAction)
                        QuickActionChip("Back", "now go back", onQuickAction)
                        QuickActionChip("Clear Chat", "clear", { onClearHistory() })
                    }
                }
            }
        }
    }
}

@Composable
private fun DynamicVoiceWaveformCard(
    mode: BubbleMode,
    rmsLevel: Float,
    recognizedText: String,
    spokenText: String
) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform_card")
    val clampedRms = rmsLevel.coerceIn(0f, 1f)

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0xF0080C14),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (mode == BubbleMode.LISTENING) CyanListening.copy(alpha = 0.8f) else EmeraldSpeaking.copy(alpha = 0.8f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Live Status Text
            Text(
                text = if (mode == BubbleMode.LISTENING) {
                    if (recognizedText.isNotBlank()) "\"$recognizedText\"" else "Listening... Speak your command"
                } else {
                    if (spokenText.isNotBlank()) spokenText else "EVA Speaking..."
                },
                color = if (mode == BubbleMode.LISTENING) CyanListening else EmeraldSpeaking,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(6.dp))

            // 15 Dynamic Reactive Waveform Bars
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val barCount = 15
                for (i in 0 until barCount) {
                    // Phase offset based on index creates realistic traveling audio wave
                    val duration = 160 + (i % 5) * 35
                    val baseHeight = 4f + (if (i % 2 == 0) 3f else 1.5f)
                    val boost = if (mode == BubbleMode.LISTENING) clampedRms * 16f else 10f

                    val animatedHeight by infiniteTransition.animateFloat(
                        initialValue = baseHeight,
                        targetValue = (baseHeight + boost * (0.6f + (i % 4) * 0.15f)).coerceIn(4f, 24f),
                        animationSpec = infiniteRepeatable(
                            animation = tween(duration, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "wave_bar_$i"
                    )

                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(animatedHeight.dp)
                            .clip(RoundedCornerShape(1.5.dp))
                            .background(
                                Brush.verticalGradient(
                                    if (mode == BubbleMode.LISTENING) {
                                        listOf(CyanListening, GoldAccent)
                                    } else {
                                        listOf(EmeraldSpeaking, GoldAccent)
                                    }
                                )
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickActionChip(
    label: String,
    command: String,
    onClick: (String) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = Color(0x22FFFFFF),
        modifier = Modifier.clickable { onClick(command) }
    ) {
        Text(
            text = label,
            color = Color(0xFFCBD5E1),
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun ListeningAudioWave(rmsLevel: Float) {
    val infiniteTransition = rememberInfiniteTransition(label = "audio_wave")
    val clampedRms = rmsLevel.coerceIn(0f, 1f)

    val h1 by infiniteTransition.animateFloat(
        initialValue = 4f,
        targetValue = (5f + clampedRms * 12f).coerceIn(4f, 18f),
        animationSpec = infiniteRepeatable(tween(160), RepeatMode.Reverse),
        label = "w1"
    )
    val h2 by infiniteTransition.animateFloat(
        initialValue = 6f,
        targetValue = (8f + clampedRms * 16f).coerceIn(5f, 20f),
        animationSpec = infiniteRepeatable(tween(210), RepeatMode.Reverse),
        label = "w2"
    )
    val h3 by infiniteTransition.animateFloat(
        initialValue = 5f,
        targetValue = (7f + clampedRms * 14f).coerceIn(4f, 18f),
        animationSpec = infiniteRepeatable(tween(180), RepeatMode.Reverse),
        label = "w3"
    )
    val h4 by infiniteTransition.animateFloat(
        initialValue = 7f,
        targetValue = (10f + clampedRms * 18f).coerceIn(6f, 22f),
        animationSpec = infiniteRepeatable(tween(230), RepeatMode.Reverse),
        label = "w4"
    )
    val h5 by infiniteTransition.animateFloat(
        initialValue = 4f,
        targetValue = (6f + clampedRms * 13f).coerceIn(4f, 17f),
        animationSpec = infiniteRepeatable(tween(190), RepeatMode.Reverse),
        label = "w5"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.height(18.dp)
    ) {
        listOf(h1, h2, h4, h3, h5).forEach { barH ->
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height(barH.dp)
                    .background(
                        Brush.verticalGradient(listOf(CyanListening, GoldAccent)),
                        RoundedCornerShape(1.dp)
                    )
            )
        }
    }
}

@Composable
private fun SpeakingEqualizerBars() {
    val infiniteTransition = rememberInfiniteTransition(label = "speaking_bars")
    val barHeight1 by infiniteTransition.animateFloat(
        initialValue = 3f,
        targetValue = 14f,
        animationSpec = infiniteRepeatable(tween(220, easing = LinearEasing), RepeatMode.Reverse),
        label = "s_bar1"
    )
    val barHeight2 by infiniteTransition.animateFloat(
        initialValue = 14f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(tween(260, easing = LinearEasing), RepeatMode.Reverse),
        label = "s_bar2"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.height(16.dp)
    ) {
        Box(
            modifier = Modifier
                .width(2.5.dp)
                .height(barHeight1.dp)
                .background(EmeraldSpeaking, RoundedCornerShape(1.dp))
        )
        Box(
            modifier = Modifier
                .width(2.5.dp)
                .height(barHeight2.dp)
                .background(EmeraldSpeaking, RoundedCornerShape(1.dp))
        )
        Box(
            modifier = Modifier
                .width(2.5.dp)
                .height(barHeight1.dp)
                .background(EmeraldSpeaking, RoundedCornerShape(1.dp))
        )
    }
}
