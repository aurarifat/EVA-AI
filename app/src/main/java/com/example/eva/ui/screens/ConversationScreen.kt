package com.example.eva.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.eva.data.database.ConversationEntity
import com.example.eva.ui.EvaViewModel
import com.example.eva.voice.VoiceState
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    viewModel: EvaViewModel,
    onBack: () -> Unit
) {
    val conversations by viewModel.conversations.collectAsState()
    val voiceState by viewModel.voiceState.collectAsState()
    val isSpeaking by viewModel.isSpeaking.collectAsState()
    val speechText by viewModel.speechText.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Auto-scroll on new message
    LaunchedEffect(conversations.size) {
        if (conversations.isNotEmpty()) {
            listState.animateScrollToItem(conversations.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (isSpeaking) EvaYellowPrimary else EvaSuccessGreen)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "EVA Assistant",
                                color = EvaTextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isSpeaking) "Speaking..." else if (voiceState == VoiceState.LISTENING) "Listening..." else "Ready",
                                color = if (isSpeaking) EvaYellowPrimary else EvaTextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = EvaTextPrimary)
                    }
                },
                actions = {
                    if (isSpeaking) {
                        IconButton(onClick = { viewModel.stopSpeaking() }) {
                            Icon(Icons.Default.VolumeOff, contentDescription = "Mute", tint = EvaYellowPrimary)
                        }
                    }
                    IconButton(onClick = { viewModel.clearHistory() }) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Clear History", tint = EvaTextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = EvaObsidian
                )
            )
        },
        bottomBar = {
            Surface(
                color = EvaSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x22FFD54F))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    if (voiceState == VoiceState.LISTENING && speechText.isNotBlank()) {
                        Text(
                            text = "Hearing: \"$speechText\"",
                            color = EvaCyanAccent,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            placeholder = { Text("Ask EVA anything or enter command...", color = EvaTextTertiary, fontSize = 13.sp) },
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(24.dp)),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = EvaSurfaceElevated,
                                unfocusedContainerColor = EvaSurfaceElevated,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedTextColor = EvaTextPrimary,
                                unfocusedTextColor = EvaTextPrimary
                            ),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        // Mic button
                        IconButton(
                            onClick = {
                                if (voiceState == VoiceState.LISTENING) {
                                    viewModel.stopListening()
                                } else {
                                    viewModel.startListening()
                                }
                            },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (voiceState == VoiceState.LISTENING) EvaErrorRed else EvaSurfaceElevated)
                        ) {
                            Icon(
                                imageVector = if (voiceState == VoiceState.LISTENING) Icons.Default.Stop else Icons.Default.Mic,
                                contentDescription = "Voice Input",
                                tint = if (voiceState == VoiceState.LISTENING) Color.White else EvaYellowPrimary
                            )
                        }

                        // Send button
                        IconButton(
                            onClick = {
                                if (inputText.isNotBlank()) {
                                    val cmd = inputText
                                    inputText = ""
                                    viewModel.processCommand(cmd)
                                }
                            },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(EvaYellowPrimary)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = "Send",
                                tint = Color(0xFF090A0E),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        },
        containerColor = EvaObsidian
    ) { padding ->
        if (conversations.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.ChatBubbleOutline,
                        contentDescription = null,
                        tint = EvaTextTertiary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No conversation yet",
                        color = EvaTextSecondary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Say \"EVA, open YouTube\" or tap the mic below.",
                        color = EvaTextTertiary,
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(conversations, key = { it.id }) { msg ->
                    ConversationBubble(
                        message = msg,
                        onRepeat = { viewModel.repeatLastResponse() },
                        onCopy = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("EVA message", msg.content))
                            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ConversationBubble(
    message: ConversationEntity,
    onRepeat: () -> Unit,
    onCopy: () -> Unit
) {
    val isUser = message.role == "user"
    val timeStr = remember(message.timestamp) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp))
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        // Tool execution indicator if message had a tool
        if (!message.toolName.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = EvaSurfaceElevated,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x33FFD54F)),
                modifier = Modifier.padding(bottom = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (message.toolStatus == "success") Icons.Default.CheckCircle else Icons.Default.Build,
                        contentDescription = null,
                        tint = if (message.toolStatus == "success") EvaSuccessGreen else EvaYellowPrimary,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${message.toolName} ${if (message.toolStatus == "success") "executed" else "invoked"}",
                        color = EvaTextSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // Message bubble
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            color = if (isUser) EvaYellowPrimary else EvaSurfaceElevated,
            border = if (isUser) null else androidx.compose.foundation.BorderStroke(1.dp, Color(0x22FFFFFF)),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = message.content,
                    color = if (isUser) Color(0xFF090A0E) else EvaTextPrimary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = timeStr,
                        color = if (isUser) Color(0x99090A0E) else EvaTextTertiary,
                        fontSize = 10.sp
                    )
                    if (!isUser) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy",
                            tint = EvaTextTertiary,
                            modifier = Modifier
                                .size(13.dp)
                                .clickable(onClick = onCopy)
                        )
                    }
                }
            }
        }
    }
}
