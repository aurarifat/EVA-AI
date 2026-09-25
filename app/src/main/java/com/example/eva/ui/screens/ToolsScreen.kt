package com.example.eva.ui.screens

import android.content.Intent
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.eva.tools.ToolCategory
import com.example.eva.ui.EvaViewModel
import com.example.eva.ui.components.EvaGlassCard
import com.example.ui.theme.*

data class ToolCardItem(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val category: ToolCategory,
    val command: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    viewModel: EvaViewModel,
    onBack: () -> Unit,
    onNavigateToShizuku: () -> Unit,
    onNavigateToApps: () -> Unit,
    onNavigateToSchedule: () -> Unit
) {
    val context = LocalContext.current
    var selectedCategory by remember { mutableStateOf<ToolCategory?>(null) }
    var volumeSlider by remember { mutableFloatStateOf(0.7f) }

    val allTools = remember {
        listOf(
            ToolCardItem("flashlight", "Flashlight Torch", "Toggle hardware LED light", Icons.Default.FlashlightOn, ToolCategory.DEVICE, "turn on flashlight"),
            ToolCardItem("volume", "Media Volume", "Set sound output level", Icons.Default.VolumeUp, ToolCategory.DEVICE, "set volume to 70"),
            ToolCardItem("device_status", "Battery & Storage", "Check system battery and free space", Icons.Default.BatteryChargingFull, ToolCategory.DEVICE, "how is my phone"),
            ToolCardItem("settings", "System Settings", "Shortcuts to Wi-Fi, Bluetooth, Audio", Icons.Default.Settings, ToolCategory.DEVICE, "open settings"),
            ToolCardItem("apps", "Installed Applications", "Search and launch device apps", Icons.Default.Apps, ToolCategory.APPS, "open apps"),
            ToolCardItem("music", "Music Player", "Scan and play local audio tracks", Icons.Default.MusicNote, ToolCategory.MEDIA, "play my music"),
            ToolCardItem("voice_memo", "Voice Recorder", "Record voice notes to disk", Icons.Default.Mic, ToolCategory.MEDIA, "start voice recording"),
            ToolCardItem("ip", "Public IP & Net", "Inspect network address and ping", Icons.Default.Speed, ToolCategory.INTERNET, "what's my IP"),
            ToolCardItem("wiki", "Wikipedia Search", "Lookup encyclopedia summaries", Icons.Default.MenuBook, ToolCategory.INTERNET, "who was Albert Einstein"),
            ToolCardItem("news", "News Bulletin", "Curated Tech & regional headlines", Icons.Default.Feed, ToolCategory.INTERNET, "what's today's news"),
            ToolCardItem("contacts", "Contacts Search", "Find address book entries", Icons.Default.Contacts, ToolCategory.COMMUNICATION, "find contacts"),
            ToolCardItem("shizuku", "Shizuku Bridge", "Wireless debugging and diagnostics", Icons.Default.Cable, ToolCategory.ADVANCED, "shizuku status"),
            ToolCardItem("gaming", "Gaming Mode", "Optimize sound and launch games", Icons.Default.SportsEsports, ToolCategory.AUTOMATION, "gaming mode"),
            ToolCardItem("study", "Study Mode", "Focus schedule and quiet audio", Icons.Default.School, ToolCategory.AUTOMATION, "study mode")
        )
    }

    val categories = ToolCategory.values().toList()
    val filteredTools = if (selectedCategory == null) allTools else allTools.filter { it.category == selectedCategory }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tools & Capabilities", color = EvaTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
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
            // Volume Quick Controller Card
            EvaGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("MEDIA AUDIO LEVEL", color = EvaYellowGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("${(volumeSlider * 100).toInt()}%", color = EvaYellowPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = volumeSlider,
                        onValueChange = {
                            volumeSlider = it
                            viewModel.deviceTools.setVolume((it * 100).toInt())
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = EvaYellowPrimary,
                            activeTrackColor = EvaYellowPrimary,
                            inactiveTrackColor = EvaSurfaceHighlight
                        )
                    )
                }
            }

            // Category Filter Pills
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (selectedCategory == null) EvaYellowPrimary else EvaSurfaceElevated,
                    modifier = Modifier.clickable { selectedCategory = null }
                ) {
                    Text(
                        text = "All",
                        color = if (selectedCategory == null) Color(0xFF090A0E) else EvaTextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                listOf(ToolCategory.DEVICE, ToolCategory.MEDIA, ToolCategory.INTERNET, ToolCategory.ADVANCED).forEach { cat ->
                    val isSelected = selectedCategory == cat
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) EvaYellowPrimary else EvaSurfaceElevated,
                        modifier = Modifier.clickable { selectedCategory = cat }
                    ) {
                        Text(
                            text = cat.title.substringBefore(" "),
                            color = if (isSelected) Color(0xFF090A0E) else EvaTextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            // Tool Items Grid/List
            for (tool in filteredTools) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = EvaSurface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x22FFFFFF)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            when (tool.id) {
                                "shizuku" -> onNavigateToShizuku()
                                "apps" -> onNavigateToApps()
                                else -> {
                                    viewModel.processCommand(tool.command)
                                    Toast.makeText(context, "Executing ${tool.title}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = EvaSurfaceElevated,
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(tool.icon, contentDescription = null, tint = EvaYellowPrimary, modifier = Modifier.size(22.dp))
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(tool.title, color = EvaTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(tool.description, color = EvaTextSecondary, fontSize = 12.sp)
                        }

                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = EvaTextTertiary, modifier = Modifier.size(20.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
