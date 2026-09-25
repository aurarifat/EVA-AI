package com.example.eva.ui.screens

import android.app.TimePickerDialog
import android.widget.Toast
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.eva.data.database.ScheduledTaskEntity
import com.example.eva.ui.EvaViewModel
import com.example.eva.ui.components.EvaGlassCard
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    viewModel: EvaViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val tasks by viewModel.scheduledTasks.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var taskTitle by remember { mutableStateOf("") }
    var taskDetails by remember { mutableStateOf("") }
    var selectedCalendar by remember { mutableStateOf(Calendar.getInstance()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Schedule & Automations", color = EvaTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = EvaTextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Task", tint = EvaYellowPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = EvaObsidian)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = EvaYellowPrimary,
                contentColor = Color(0xFF090A0E)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add")
            }
        },
        containerColor = EvaObsidian
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // Preset Automations Card
            item {
                EvaGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("BUILT-IN SMART MODES", color = EvaYellowGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(10.dp))

                        val modes = listOf(
                            Triple("Gaming Mode", "Set volume to 80% & background optimize", "gaming mode"),
                            Triple("Study Mode", "Focus schedule, soften audio & launch study app", "study mode"),
                            Triple("Night Mode", "Quiet 15% audio and restful timer", "night mode")
                        )

                        for ((title, desc, cmd) in modes) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = EvaSurfaceElevated,
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x1AFFFFFF)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        viewModel.processCommand(cmd)
                                        Toast.makeText(context, "Activated $title", Toast.LENGTH_SHORT).show()
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Tune, contentDescription = null, tint = EvaYellowPrimary, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(title, color = EvaTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                        Text(desc, color = EvaTextSecondary, fontSize = 11.sp)
                                    }
                                    Text("RUN", color = EvaYellowPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // Tasks Header
            item {
                Text(
                    text = "SCHEDULED TASKS & REMINDERS (${tasks.size})",
                    color = EvaTextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }

            if (tasks.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = EvaSurface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x1AFFFFFF)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.EventNote, contentDescription = null, tint = EvaTextTertiary, modifier = Modifier.size(36.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("No upcoming scheduled tasks", color = EvaTextSecondary, fontSize = 14.sp)
                            Text("Say \"Remind me at 8 PM to study physics\" or tap + below.", color = EvaTextTertiary, fontSize = 12.sp)
                        }
                    }
                }
            } else {
                items(tasks, key = { it.id }) { task ->
                    val timeStr = remember(task.timeMillis) {
                        SimpleDateFormat("MMM dd, yyyy - hh:mm a", Locale.getDefault()).format(Date(task.timeMillis))
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = EvaSurface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x22FFFFFF)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(14.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Alarm, contentDescription = null, tint = EvaYellowPrimary, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(task.title, color = EvaTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                if (task.details.isNotBlank()) {
                                    Text(task.details, color = EvaTextSecondary, fontSize = 12.sp)
                                }
                                Text(timeStr, color = EvaCyanAccent, fontSize = 11.sp)
                            }
                            IconButton(onClick = { viewModel.deleteTask(task) }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = EvaTextTertiary)
                            }
                        }
                    }
                }
            }
        }

        // Add Task Dialog
        if (showAddDialog) {
            AlertDialog(
                onDismissRequest = { showAddDialog = false },
                title = { Text("Schedule Reminder / Task", color = EvaTextPrimary) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = taskTitle,
                            onValueChange = { taskTitle = it },
                            label = { Text("Task Title") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = taskDetails,
                            onValueChange = { taskDetails = it },
                            label = { Text("Notes / Details (Optional)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                TimePickerDialog(
                                    context,
                                    { _, hour, minute ->
                                        selectedCalendar.set(Calendar.HOUR_OF_DAY, hour)
                                        selectedCalendar.set(Calendar.MINUTE, minute)
                                    },
                                    selectedCalendar.get(Calendar.HOUR_OF_DAY),
                                    selectedCalendar.get(Calendar.MINUTE),
                                    false
                                ).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = EvaSurfaceElevated, contentColor = EvaYellowPrimary),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Schedule, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Pick Time")
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (taskTitle.isNotBlank()) {
                                viewModel.addTask(taskTitle, taskDetails, selectedCalendar.timeInMillis)
                                taskTitle = ""
                                taskDetails = ""
                                showAddDialog = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = EvaYellowPrimary, contentColor = Color(0xFF090A0E))
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddDialog = false }) {
                        Text("Cancel", color = EvaTextSecondary)
                    }
                },
                containerColor = EvaSurfaceElevated
            )
        }
    }
}
