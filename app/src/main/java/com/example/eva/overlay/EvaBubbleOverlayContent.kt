package com.example.eva.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R

// High-end Obsidian & Gold Theme Palette
private val BubbleObsidian = Color(0xF00D1117)
private val BubbleSurface = Color(0xEE161B22)
private val GoldAccent = Color(0xFFF6D860)
private val CyanListening = Color(0xFF00E5FF)
private val EmeraldSpeaking = Color(0xFF00E676)
private val SubtextGray = Color(0xFF8B949E)

@Composable
fun EvaBubbleOverlayContent(
    state: BubbleOverlayUiState,
    onDragDelta: (dx: Float, dy: Float) -> Unit,
    onBubbleTap: () -> Unit,
    onLogoClick: () -> Unit,
    onOpenApp: () -> Unit,
    onToggleExpand: () -> Unit,
    onQuickAction: (String) -> Unit,
    onCloseOverlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "bubble_animations")

    // Pulse animation for breathing/listening/speaking
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = when (state.mode) {
            BubbleMode.LISTENING -> 1.15f
            BubbleMode.SPEAKING -> 1.08f
            BubbleMode.IDLE -> 1.03f
        },
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (state.mode) {
                    BubbleMode.LISTENING -> 600
                    BubbleMode.SPEAKING -> 800
                    BubbleMode.IDLE -> 1800
                },
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    // Glow alpha
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = when (state.mode) {
            BubbleMode.LISTENING -> 0.95f
            BubbleMode.SPEAKING -> 0.85f
            BubbleMode.IDLE -> 0.45f
        },
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )

    Column(
        modifier = modifier
            .padding(6.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDragDelta(dragAmount.x, dragAmount.y)
                }
            }
    ) {
        // Main Floating Capsule (Bubble + Status Pill)
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = BubbleObsidian,
            shadowElevation = 12.dp,
            border = androidx.compose.foundation.BorderStroke(
                width = 1.5.dp,
                brush = Brush.horizontalGradient(
                    colors = when (state.mode) {
                        BubbleMode.LISTENING -> listOf(CyanListening, GoldAccent)
                        BubbleMode.SPEAKING -> listOf(EmeraldSpeaking, CyanListening)
                        BubbleMode.IDLE -> listOf(GoldAccent.copy(alpha = 0.7f), Color.White.copy(alpha = 0.5f))
                    }
                )
            ),
            modifier = Modifier
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
            ) {
                // Premium White Logo Emblem Container with dynamic glow (Clicking toggles to Home Screen)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .scale(pulseScale)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onLogoClick
                        )
                ) {
                    // Outer Glowing Halo
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(
                                when (state.mode) {
                                    BubbleMode.LISTENING -> CyanListening.copy(alpha = glowAlpha * 0.35f)
                                    BubbleMode.SPEAKING -> EmeraldSpeaking.copy(alpha = glowAlpha * 0.35f)
                                    BubbleMode.IDLE -> Color.White.copy(alpha = glowAlpha * 0.2f)
                                }
                            )
                    )

                    // Logo Icon Frame - Tap toggles to home screen
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(BubbleSurface)
                            .border(
                                width = 2.dp,
                                color = when (state.mode) {
                                    BubbleMode.LISTENING -> CyanListening
                                    BubbleMode.SPEAKING -> EmeraldSpeaking
                                    BubbleMode.IDLE -> Color.White
                                },
                                shape = CircleShape
                            )
                            .padding(6.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_eva_white_logo),
                            contentDescription = "EVA Logo - Click to Toggle Home Screen",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                // Information & State Column - Tap starts voice listening / interaction
                Column(
                    modifier = Modifier
                        .widthIn(min = 90.dp, max = 220.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onBubbleTap
                        )
                ) {
                    // Mode Tag Row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Animated Status Dot
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(
                                    when (state.mode) {
                                        BubbleMode.LISTENING -> CyanListening
                                        BubbleMode.SPEAKING -> EmeraldSpeaking
                                        BubbleMode.IDLE -> GoldAccent
                                    }
                                )
                        )

                        Text(
                            text = when (state.mode) {
                                BubbleMode.LISTENING -> "LISTENING"
                                BubbleMode.SPEAKING -> "SPEAKING"
                                BubbleMode.IDLE -> "EVA READY"
                            },
                            color = when (state.mode) {
                                BubbleMode.LISTENING -> CyanListening
                                BubbleMode.SPEAKING -> EmeraldSpeaking
                                BubbleMode.IDLE -> GoldAccent
                            },
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )

                        if (state.mode == BubbleMode.SPEAKING) {
                            SpeakingEqualizerBars()
                        } else if (state.mode == BubbleMode.LISTENING) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Listening",
                                tint = CyanListening,
                                modifier = Modifier.size(11.dp)
                            )
                        }
                    }

                    // Main Status Text
                    Text(
                        text = if (state.mode == BubbleMode.LISTENING && state.recognizedText.isNotBlank()) {
                            "\"${state.recognizedText}\""
                        } else {
                            state.statusText
                        },
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Secondary Subtext
                    Text(
                        text = when (state.mode) {
                            BubbleMode.IDLE -> "Tap to speak • Hold for actions"
                            BubbleMode.LISTENING -> "Listening to your voice..."
                            BubbleMode.SPEAKING -> "Executing & speaking..."
                        },
                        color = SubtextGray,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Expand/Actions Toggle Button
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.1f))
                        .clickable { onToggleExpand() }
                ) {
                    Text(
                        text = if (state.isExpanded) "▲" else "▼",
                        color = GoldAccent,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Expandable Quick Action Chips Row
        AnimatedVisibility(
            visible = state.isExpanded,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(BubbleObsidian)
                    .border(1.dp, GoldAccent.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                    .padding(8.dp)
                    .widthIn(max = 280.dp)
            ) {
                Text(
                    text = "QUICK SHIZUKU ACTIONS",
                    color = GoldAccent,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 6.dp, start = 2.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OverlayActionButton(
                        label = "Toggle Home",
                        color = CyanListening,
                        onClick = onLogoClick
                    )

                    OverlayActionButton(
                        label = "Open App",
                        color = GoldAccent,
                        onClick = onOpenApp
                    )

                    OverlayActionButton(
                        label = "Close Ads",
                        color = Color(0xFFFF9100),
                        onClick = { onQuickAction("close ads") }
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OverlayActionButton(
                        label = "Protect ON",
                        color = EmeraldSpeaking,
                        onClick = { onQuickAction("turn the protection on") }
                    )

                    OverlayActionButton(
                        label = "AdGuard",
                        color = Color(0xFF64B5F6),
                        onClick = { onQuickAction("open adguard") }
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (state.isShizukuActive) "✓ Shizuku shell ready" else "• Shizuku background",
                        color = if (state.isShizukuActive) EmeraldSpeaking else SubtextGray,
                        fontSize = 9.sp
                    )

                    // Close overlay button
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Red.copy(alpha = 0.2f))
                            .clickable { onCloseOverlay() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color(0xFFFF5252),
                            modifier = Modifier.size(11.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "Exit",
                            color = Color(0xFFFF5252),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlayActionButton(
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.15f),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.6f)),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun SpeakingEqualizerBars() {
    val infiniteTransition = rememberInfiniteTransition(label = "eq_bars")
    val h1 by infiniteTransition.animateFloat(
        initialValue = 4f,
        targetValue = 12f,
        animationSpec = infiniteRepeatable(tween(300, easing = LinearEasing), RepeatMode.Reverse),
        label = "h1"
    )
    val h2 by infiniteTransition.animateFloat(
        initialValue = 10f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(tween(250, easing = LinearEasing), RepeatMode.Reverse),
        label = "h2"
    )
    val h3 by infiniteTransition.animateFloat(
        initialValue = 6f,
        targetValue = 14f,
        animationSpec = infiniteRepeatable(tween(350, easing = LinearEasing), RepeatMode.Reverse),
        label = "h3"
    )

    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(1.5.dp),
        modifier = Modifier.height(14.dp)
    ) {
        Box(modifier = Modifier.width(2.dp).height(h1.dp).background(EmeraldSpeaking, RoundedCornerShape(1.dp)))
        Box(modifier = Modifier.width(2.dp).height(h2.dp).background(EmeraldSpeaking, RoundedCornerShape(1.dp)))
        Box(modifier = Modifier.width(2.dp).height(h3.dp).background(EmeraldSpeaking, RoundedCornerShape(1.dp)))
    }
}
