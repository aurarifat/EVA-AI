package com.example.eva.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.eva.voice.VoiceState
import com.example.ui.theme.*

@Composable
fun EvaNexusOrb(
    voiceState: VoiceState,
    rmsLevel: Float,
    isSpeaking: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "nexus_anim")

    // Ambient pulsing glow
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    // Outer ring rotation
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    // Audio reactivity expansion
    val activeScale = when (voiceState) {
        VoiceState.LISTENING -> 1.0f + (rmsLevel * 0.4f)
        VoiceState.SPEAKING -> pulseScale * 1.12f
        VoiceState.EXECUTING, VoiceState.THINKING -> pulseScale * 1.06f
        VoiceState.SLEEPING -> 0.88f
        else -> pulseScale
    }

    val glowColor = when (voiceState) {
        VoiceState.LISTENING -> EvaCyanAccent
        VoiceState.SPEAKING -> EvaYellowPrimary
        VoiceState.THINKING, VoiceState.EXECUTING -> EvaYellowGold
        VoiceState.SLEEPING -> Color(0xFF475569)
        VoiceState.ERROR -> EvaErrorRed
        VoiceState.IDLE -> EvaYellowPrimary
    }

    Box(
        modifier = modifier
            .size(200.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // Outer wave rings
        Canvas(modifier = Modifier.fillMaxSize().scale(activeScale)) {
            val center = this.center
            val radius = size.minDimension / 2.3f

            // Outer subtle boundary
            drawCircle(
                color = glowColor.copy(alpha = 0.15f),
                radius = radius + 20f,
                style = Stroke(width = 2.dp.toPx())
            )

            // Inner rotating arc
            drawCircle(
                color = glowColor.copy(alpha = 0.35f),
                radius = radius + 6f,
                style = Stroke(width = 3.dp.toPx())
            )

            // Core glow gradient
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        glowColor.copy(alpha = 0.85f),
                        glowColor.copy(alpha = 0.35f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = radius
                ),
                radius = radius
            )
        }

        // Inner glowing orb button
        Box(
            modifier = Modifier
                .size(110.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF221E12),
                            EvaSurface,
                            EvaObsidian
                        )
                    )
                )
                .border(
                    BorderStroke(2.dp, Brush.linearGradient(listOf(glowColor, glowColor.copy(alpha = 0.3f)))),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = when (voiceState) {
                    VoiceState.LISTENING -> Icons.Default.Mic
                    VoiceState.SPEAKING -> Icons.Default.VolumeUp
                    VoiceState.THINKING, VoiceState.EXECUTING -> Icons.Default.AutoAwesome
                    VoiceState.SLEEPING -> Icons.Default.Bedtime
                    VoiceState.ERROR -> Icons.Default.Warning
                    VoiceState.IDLE -> Icons.Default.GraphicEq
                },
                contentDescription = "EVA Voice Status",
                tint = glowColor,
                modifier = Modifier.size(42.dp)
            )
        }
    }
}

@Composable
fun EvaGlassCard(
    modifier: Modifier = Modifier,
    borderStroke: BorderStroke? = BorderStroke(1.dp, Color(0x33FFD54F)),
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = EvaSurface.copy(alpha = 0.85f)
        ),
        border = borderStroke,
        content = content
    )
}

@Composable
fun EvaStatusChip(
    text: String,
    icon: ImageVector,
    color: Color = EvaYellowPrimary,
    onClick: (() -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = EvaSurfaceElevated,
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f)),
        modifier = Modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                color = EvaTextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
