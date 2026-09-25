package com.example.eva.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.asImageBitmap
import com.example.eva.tools.InstalledAppInfo
import com.example.eva.ui.EvaViewModel
import com.example.ui.theme.*
import kotlinx.coroutines.launch

fun Drawable.toBitmap(): Bitmap {
    if (this is BitmapDrawable && bitmap != null) return bitmap
    val w = intrinsicWidth.coerceAtLeast(1)
    val h = intrinsicHeight.coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bmp
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLauncherScreen(
    viewModel: EvaViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var installedApps by remember { mutableStateOf<List<InstalledAppInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        scope.launch {
            installedApps = viewModel.appLauncherTools.getInstalledLaunchableApps()
            isLoading = false
        }
    }

    val filtered = remember(searchQuery, installedApps) {
        if (searchQuery.isBlank()) installedApps
        else installedApps.filter {
            it.appName.contains(searchQuery, ignoreCase = true) ||
                    it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Application Launcher", color = EvaTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
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
                .padding(horizontal = 16.dp)
        ) {
            // Search Input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search installed applications...", fontSize = 13.sp, color = EvaTextTertiary) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = EvaYellowPrimary) },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = null, tint = EvaTextSecondary)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = EvaYellowPrimary,
                    unfocusedBorderColor = Color(0x33FFFFFF),
                    focusedContainerColor = EvaSurface,
                    unfocusedContainerColor = EvaSurface
                ),
                singleLine = true
            )

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = EvaYellowPrimary)
                }
            } else {
                Text(
                    text = "${filtered.size} Applications available",
                    color = EvaTextTertiary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 6.dp)
                )

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(filtered, key = { it.packageName }) { app ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = EvaSurface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x1AFFFFFF)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val res = viewModel.appLauncherTools.launchAppByPackage(app.packageName)
                                    if (res.isSuccess) {
                                        viewModel.contextManager.updateActiveApp(app.appName, app.packageName)
                                        Toast.makeText(context, "Opening ${app.appName}", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, res.message, Toast.LENGTH_SHORT).show()
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(12.dp)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (app.icon != null) {
                                    val iconBitmap = remember(app.icon) { app.icon.toBitmap().asImageBitmap() }
                                    Image(
                                        bitmap = iconBitmap,
                                        contentDescription = app.appName,
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                    )
                                } else {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = EvaSurfaceElevated,
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.Apps, contentDescription = null, tint = EvaYellowPrimary)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(app.appName, color = EvaTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    Text(app.packageName, color = EvaTextTertiary, fontSize = 11.sp)
                                }

                                Icon(Icons.Default.Launch, contentDescription = "Launch", tint = EvaYellowPrimary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
