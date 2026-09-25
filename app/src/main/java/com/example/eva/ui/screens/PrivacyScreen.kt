package com.example.eva.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.eva.ui.EvaViewModel
import com.example.eva.ui.components.EvaGlassCard
import com.example.ui.theme.*

data class PermissionItem(
    val title: String,
    val permission: String?,
    val icon: ImageVector,
    val explanation: String,
    val isHardwareProtected: Boolean = true
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(
    viewModel: EvaViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    val permissionsList = remember {
        listOf(
            PermissionItem(
                "Microphone Access",
                Manifest.permission.RECORD_AUDIO,
                Icons.Default.Mic,
                "Used exclusively when you press push-to-talk or trigger voice recording. EVA never secretly records."
            ),
            PermissionItem(
                "Camera & Flashlight",
                Manifest.permission.CAMERA,
                Icons.Default.CameraAlt,
                "Used for LED torch toggle, QR scanning, and CameraX snapshots. Never secretly activated."
            ),
            PermissionItem(
                "Address Book / Contacts",
                Manifest.permission.READ_CONTACTS,
                Icons.Default.Contacts,
                "Used to resolve contact names when you say 'Call John' or 'Find Mary'."
            ),
            PermissionItem(
                "Precise Location",
                Manifest.permission.ACCESS_FINE_LOCATION,
                Icons.Default.LocationOn,
                "Used only upon request (e.g. 'Where am I?') to provide coordinates or map links."
            ),
            PermissionItem(
                "Notifications",
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
                    Manifest.permission.POST_NOTIFICATIONS else null,
                Icons.Default.Notifications,
                "Delivers your scheduled reminders, timer alarms, and active recording notices."
            ),
            PermissionItem(
                "Shizuku IPC Bridge",
                null,
                Icons.Default.Cable,
                "Runs through official Shizuku IPC binder protocols (`moe.shizuku.privileged.api`). Only accesses registered diagnostic telemetry."
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy Center & Permissions", color = EvaTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
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
            // Guarantee Card
            EvaGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("EVA PRIVACY CHARTER", color = EvaYellowGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "EVA is built on zero-surveillance principles. Your conversations and voice samples are processed directly or sent only to your chosen AI provider using your own secure API keys. No private audio or camera frames are ever retained without your instruction.",
                        color = EvaTextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
            }

            Text("DEVICE ACCESS PERMISSIONS", color = EvaTextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)

            for (item in permissionsList) {
                val isGranted = if (item.permission != null) {
                    ContextCompat.checkSelfPermission(context, item.permission) == PackageManager.PERMISSION_GRANTED
                } else {
                    viewModel.shizukuState.value.isAuthorized
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = EvaSurface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x1AFFFFFF)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(item.icon, contentDescription = null, tint = EvaYellowPrimary, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(item.title, color = EvaTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            }

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isGranted) EvaSuccessGreen.copy(alpha = 0.2f) else Color(0x3364748B),
                                border = androidx.compose.foundation.BorderStroke(1.dp, if (isGranted) EvaSuccessGreen else Color(0xFF64748B))
                            ) {
                                Text(
                                    text = if (isGranted) "GRANTED" else "NOT GRANTED",
                                    color = if (isGranted) EvaSuccessGreen else EvaTextTertiary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(item.explanation, color = EvaTextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                }
            }

            Button(
                onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                },
                colors = ButtonDefaults.buttonColors(containerColor = EvaSurfaceElevated, contentColor = EvaYellowPrimary),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x33FFD54F)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Open System App Permissions")
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
