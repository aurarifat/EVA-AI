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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotInterested
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import kotlin.math.sqrt

// High-end Luxury Palette
private val BubbleObsidian = Color(0xF20B0F17)
private val BubbleCardSurface = Color(0xF0121722)
private val GoldAccent = Color(0xFFF6D860)
private val GoldBorder = Color(0xFFD4AF37)
private val CyanListening = Color(0xFF00E5FF)
private val EmeraldSpeaking = Color(0xFF00E676)
private val SubtextGray = Color(0xFF8B949E)

@Composable
fun EvaBubbleOverlayContent(
    state: BubbleOverlayUiState,
    onDragDelta: (dx: Float, dy: Float) -> Unit,
    onBubbleClick: () -> Unit,
    onStartVoice: () -> Unit,
    onToggleExpand: () -> Unit,
    onQuickAction: (String) -> Unit,
    onOpenApp: () -> Unit,
    onCloseOverlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "bubble_animations")

    // Dynamic pulse for bubble
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = when (state.mode) {
            BubbleMode.LISTENING -> 1.12f
            BubbleMode.SPEAKING -> 1.07f
            BubbleMode.IDLE -> 1.02f
        },
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (state.mode) {
                    BubbleMode.LISTENING -> 600
                    BubbleMode.SPEAKING -> 800
                    BubbleMode.IDLE -> 2000
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
            animation = tween(1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )

    Column(
        modifier = modifier
            .padding(4.dp)
            .widthIn(max = 240.dp)
    ) {
        // Floating Head Row (Bubble + dynamic voice indicator pill)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(2.dp)
        ) {
            // Main Compact Floating Circular Orb (56.dp)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(56.dp)
                    .scale(pulseScale)
                    .shadow(10.dp, CircleShape)
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
                                    // Finger lifted!
                                    if (!isDragging) {
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
                // Outer Glowing Halo
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(
                            when (state.mode) {
                                BubbleMode.LISTENING -> CyanListening.copy(alpha = glowAlpha * 0.40f)
                                BubbleMode.SPEAKING -> EmeraldSpeaking.copy(alpha = glowAlpha * 0.35f)
                                BubbleMode.IDLE -> GoldAccent.copy(alpha = glowAlpha * 0.25f)
                            }
                        )
                )

                // High-End Circular Frame with Zoomed Gold Logo
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(BubbleObsidian)
                        .border(
                            width = 2.dp,
                            brush = Brush.sweepGradient(
                                colors = when (state.mode) {
                                    BubbleMode.LISTENING -> listOf(CyanListening, Color.White, CyanListening)
                                    BubbleMode.SPEAKING -> listOf(EmeraldSpeaking, CyanListening, EmeraldSpeaking)
                                    BubbleMode.IDLE -> listOf(GoldAccent, Color.White, GoldBorder, GoldAccent)
                                }
                            ),
                            shape = CircleShape
                        )
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_eva_white_logo),
                        contentDescription = "EVA Bubble - Tap to Toggle Home Screen",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                    )
                }

                // Status Badge Indicator (Bottom-Right)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(14.dp)
                        .align(Alignment.BottomEnd)
                        .clip(CircleShape)
                        .background(BubbleObsidian)
                        .border(1.5.dp, Color.Black, CircleShape)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                when (state.mode) {
                                    BubbleMode.LISTENING -> CyanListening
                                    BubbleMode.SPEAKING -> EmeraldSpeaking
                                    BubbleMode.IDLE -> GoldAccent
                                }
                            )
                    )
                }
            }

            // Real-Time Active Listening / Speaking Pill (appears smoothly beside bubble)
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
                            BubbleMode.LISTENING -> CyanListening.copy(alpha = 0.8f)
                            BubbleMode.SPEAKING -> EmeraldSpeaking.copy(alpha = 0.8f)
                            else -> GoldAccent.copy(alpha = 0.5f)
                        }
                    ),
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .clickable { onBubbleClick() }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        if (state.mode == BubbleMode.LISTENING) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Listening",
                                tint = CyanListening,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (state.recognizedText.isNotBlank()) "\"${state.recognizedText}\"" else "Listening...",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else if (state.mode == BubbleMode.SPEAKING) {
                            SpeakingEqualizerBars()
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (state.spokenText.isNotBlank()) state.spokenText else "Speaking...",
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

        // Expandable Quick Action Mini-Dock
        AnimatedVisibility(
            visible = state.isExpanded,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically()
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = BubbleCardSurface,
                shadowElevation = 8.dp,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    Brush.verticalGradient(listOf(GoldAccent.copy(alpha = 0.5f), Color.Transparent))
                ),
                modifier = Modifier
                    .padding(top = 6.dp)
                    .widthIn(max = 210.dp)
            ) {
                Column(
                    modifier = Modifier.padding(8.dp)
                ) {
                    // Header with mini label
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "EVA QUICK DOCK",
                            color = GoldAccent,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )

                        Text(
                            text = if (state.isShizukuActive) "✓ Shell Ready" else "• Shizuku Active",
                            color = if (state.isShizukuActive) EmeraldSpeaking else SubtextGray,
                            fontSize = 8.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Primary Action Grid (Compact 2-columns)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CompactDockButton(
                            icon = Icons.Default.Mic,
                            label = "Voice",
                            color = CyanListening,
                            onClick = onStartVoice,
                            modifier = Modifier.weight(1f)
                        )

                        CompactDockButton(
                            icon = Icons.Default.Home,
                            label = "Home",
                            color = GoldAccent,
                            onClick = onBubbleClick,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CompactDockButton(
                            icon = Icons.Default.NotInterested,
                            label = "Close Ads",
                            color = Color(0xFFFF9100),
                            onClick = { onQuickAction("close ads") },
                            modifier = Modifier.weight(1f)
                        )

                        CompactDockButton(
                            icon = Icons.Default.Shield,
                            label = "Protect ON",
                            color = EmeraldSpeaking,
                            onClick = { onQuickAction("turn the protection on") },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Bottom Row: Open App & Dismiss
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CompactDockButton(
                            icon = Icons.Default.Apps,
                            label = "Open App",
                            color = Color.White,
                            onClick = onOpenApp,
                            modifier = Modifier.weight(1f)
                        )

                        CompactDockButton(
                            icon = Icons.Default.Close,
                            label = "Exit",
                            color = Color(0xFFFF5252),
                            onClick = onCloseOverlay,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactDockButton(
    icon: ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = color.copy(alpha = 0.12f),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.45f)),
        modifier = modifier.clickable { onClick() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = color,
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                color = color,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
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
