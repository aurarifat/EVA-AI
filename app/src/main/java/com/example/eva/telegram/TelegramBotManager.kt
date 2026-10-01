package com.example.eva.telegram

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import com.example.eva.context.CommandDispatcher
import com.example.eva.data.database.ConversationEntity
import com.example.eva.data.database.EvaDatabase
import com.example.eva.data.prefs.EvaPreferences
import com.example.eva.data.prefs.SecureKeyStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Manages Telegram Bot polling lifecycle and security enforcement.
 * STRICT ENFORCEMENT: Only the very first user to message the bot is paired as the device owner.
 * All subsequent messages from unauthorized users are strictly blocked.
 */
class TelegramBotManager(
    private val context: Context,
    private val preferences: EvaPreferences,
    private val keyStore: SecureKeyStore,
    private val commandDispatcher: CommandDispatcher,
    private val database: EvaDatabase = EvaDatabase.getInstance(context),
    private val apiClient: TelegramApiClient = TelegramApiClient()
) {

    companion object {
        private const val TAG = "TelegramBotManager"

        @Volatile
        private var instance: TelegramBotManager? = null

        fun getInstance(
            context: Context,
            preferences: EvaPreferences,
            keyStore: SecureKeyStore,
            commandDispatcher: CommandDispatcher
        ): TelegramBotManager {
            return instance ?: synchronized(this) {
                instance ?: TelegramBotManager(
                    context = context.applicationContext,
                    preferences = preferences,
                    keyStore = keyStore,
                    commandDispatcher = commandDispatcher
                ).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null

    private val _botStatus = MutableStateFlow<TelegramBotStatus>(TelegramBotStatus.Disconnected)
    val botStatus: StateFlow<TelegramBotStatus> = _botStatus.asStateFlow()

    private val _botInfo = MutableStateFlow<TelegramBotInfo?>(null)
    val botInfo: StateFlow<TelegramBotInfo?> = _botInfo.asStateFlow()

    private var lastUpdateId: Long? = null

    /**
     * Verifies the BotFather secret private code token.
     */
    suspend fun testToken(token: String): Result<TelegramBotInfo> {
        val result = apiClient.getMe(token)
        result.onSuccess {
            _botInfo.value = it
        }
        return result
    }

    /**
     * Starts the Telegram Bot polling loop if enabled and token is present.
     */
    fun startBot() {
        if (pollingJob?.isActive == true) return

        val token = keyStore.getTelegramToken()
        if (token.isBlank()) {
            _botStatus.value = TelegramBotStatus.Error("BotFather token is missing. Please enter your secret code.")
            return
        }

        _botStatus.value = TelegramBotStatus.Connecting

        pollingJob = scope.launch {
            // First verify token and get bot username
            val meResult = apiClient.getMe(token)
            if (meResult.isFailure) {
                val errorMsg = meResult.exceptionOrNull()?.message ?: "Failed to authenticate with BotFather token"
                _botStatus.value = TelegramBotStatus.Error(errorMsg)
                Log.e(TAG, "Bot authentication failed: $errorMsg")
                return@launch
            }

            val bot = meResult.getOrThrow()
            _botInfo.value = bot
            _botStatus.value = TelegramBotStatus.Running(botUsername = bot.username, isPolling = true)
            Log.i(TAG, "Telegram bot connected as @${bot.username}. Starting polling loop...")

            var backoffMillis = 1000L

            while (isActive) {
                try {
                    val settings = preferences.settingsFlow.first()
                    if (!settings.telegramEnabled) {
                        Log.i(TAG, "Telegram disabled in settings. Stopping bot loop.")
                        break
                    }

                    val updatesResult = apiClient.getUpdates(
                        token = token,
                        offset = lastUpdateId?.let { it + 1 },
                        timeoutSeconds = 20
                    )

                    if (updatesResult.isSuccess) {
                        backoffMillis = 1000L // Reset backoff on success
                        val updates = updatesResult.getOrThrow()
                        for (update in updates) {
                            lastUpdateId = update.updateId
                            update.message?.let { message ->
                                handleIncomingMessage(token, message)
                            }
                        }
                    } else {
                        val ex = updatesResult.exceptionOrNull()
                        Log.w(TAG, "Polling error: ${ex?.message}. Backing off for ${backoffMillis}ms")
                        delay(backoffMillis)
                        backoffMillis = (backoffMillis * 2).coerceAtMost(30000L)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected error in bot polling loop: ${e.message}", e)
                    delay(3000L)
                }
            }

            _botStatus.value = TelegramBotStatus.Disconnected
        }
    }

    /**
     * Stops the Telegram Bot polling loop.
     */
    fun stopBot() {
        pollingJob?.cancel()
        pollingJob = null
        _botStatus.value = TelegramBotStatus.Disconnected
        Log.i(TAG, "Telegram bot stopped.")
    }

    /**
     * Unpairs the current owner so a new owner can pair.
     */
    suspend fun unpairOwner() {
        preferences.clearTelegramPairing()
        Log.i(TAG, "Telegram owner unpaired. Next user will become owner.")
    }

    /**
     * Processes an incoming Telegram message with strict "Allow only first user" authorization.
     */
    private suspend fun handleIncomingMessage(token: String, message: TelegramMessage) {
        val chatId = message.chat.id
        val text = message.text?.trim() ?: return
        val senderUser = message.from

        val settings = preferences.settingsFlow.first()
        val currentPairedChatId = settings.telegramPairedChatId

        // =========================================================================
        // SECURITY ENFORCEMENT: "Allow only first user"
        // =========================================================================

        if (currentPairedChatId == null) {
            // CASE 1: No user has paired yet. THIS INCOMING USER IS THE FIRST USER!
            val senderName = senderUser?.displayName ?: senderUser?.username ?: senderUser?.firstName ?: "Owner"
            preferences.setTelegramPairedUser(chatId, senderName)
            Log.i(TAG, "First user paired as owner! Chat ID: $chatId, Name: $senderName")

            val welcomeMsg = buildString {
                append("🎉 *EVA Remote Control Paired Successfully!*\n\n")
                append("Welcome *$senderName*! You are the *first user* to interact with this EVA assistant and have been registered as the *Exclusive Device Owner*.\n\n")
                append("📱 *Connected Device*: ${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}\n")
                append("🤖 *System*: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
                append("🔒 *Security Status*: *Single-User Lock Active*. All messages from any other Telegram account will be blocked.\n\n")
                append("Type `/help` to see available remote tools or send any prompt to EVA!")
            }

            apiClient.sendMessage(token, chatId, welcomeMsg, parseMode = "Markdown", replyToMessageId = message.messageId)
            return
        }

        if (chatId != currentPairedChatId) {
            // CASE 2: User is NOT the registered owner. STRICT REJECTION!
            val ownerName = settings.telegramPairedUsername ?: "its device owner"
            Log.w(TAG, "Unauthorized access attempt from Chat ID: $chatId (${senderUser?.displayName}). Paired owner: $currentPairedChatId ($ownerName)")

            val rejectMsg = buildString {
                append("⛔ *Access Denied*\n\n")
                append("This EVA AI Assistant is private and securely paired with $ownerName.\n")
                append("Security policy strictly allows *only the first registered user* to control this device.\n\n")
                append("Your command was ignored.")
            }

            apiClient.sendMessage(token, chatId, rejectMsg, parseMode = "Markdown", replyToMessageId = message.messageId)
            return
        }

        // =========================================================================
        // CASE 3: User IS the paired owner. Authorized to execute!
        // =========================================================================
        val lowerText = text.lowercase()

        when {
            lowerText == "/start" || lowerText == "/pair" -> {
                val ownerName = settings.telegramPairedUsername ?: "Owner"
                val battery = getBatteryInfo()
                val resp = buildString {
                    append("👋 *Hello $ownerName!*\n\n")
                    append("EVA is online and connected to your device.\n")
                    append("🔋 *Battery*: $battery\n")
                    append("🧠 *AI Provider*: ${settings.activeProvider.name}\n\n")
                    append("Send any question, command, or action (e.g. _\"turn on flashlight\"_, _\"open YouTube\"_, _\"battery level\"_), or type `/help`.")
                }
                apiClient.sendMessage(token, chatId, resp, parseMode = "Markdown", replyToMessageId = message.messageId)
            }

            lowerText == "/help" -> {
                val helpMsg = buildString {
                    append("🤖 *EVA Remote Control Commands*\n\n")
                    append("• `/status` - Battery, network, AI model, and device info\n")
                    append("• `/battery` - Quick battery level & charging state\n")
                    append("• `/model` - View active AI model\n")
                    append("• `/tools` - List supported device automation tools\n")
                    append("• `/unpair` - Release ownership so a new user can pair\n\n")
                    append("💬 *Natural Language & Actions*\n")
                    append("You can text EVA naturally, such as:\n")
                    append("• _\"Turn on flashlight\"_\n")
                    append("• _\"Set media volume to 80%\"_\n")
                    append("• _\"Open YouTube\"_\n")
                    append("• _\"What's 45 * 128?\"_\n")
                    append("• _\"Summarize today's news\"_\n")
                    append("• Any general knowledge question")
                }
                apiClient.sendMessage(token, chatId, helpMsg, parseMode = "Markdown", replyToMessageId = message.messageId)
            }

            lowerText == "/status" -> {
                val battery = getBatteryInfo()
                val statusMsg = buildString {
                    append("📊 *EVA Device Status*\n\n")
                    append("• *Device*: ${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}\n")
                    append("• *Android*: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
                    append("• *Battery*: $battery\n")
                    append("• *Active AI*: ${settings.activeProvider.name}\n")
                    append("• *Model*: ${when (settings.activeProvider) {
                        com.example.eva.data.prefs.AiProviderType.OMNI_ROUTE -> settings.omniRouteModel
                        com.example.eva.data.prefs.AiProviderType.OPEN_ROUTER -> settings.openRouterModel
                        com.example.eva.data.prefs.AiProviderType.GEMINI -> settings.geminiModel
                        com.example.eva.data.prefs.AiProviderType.CUSTOM_OPENAI -> settings.customOpenAiModel
                    }}\n")
                    append("• *Wake Word*: ${if (settings.wakeWordEnabled) "Active (\"Hi EVA\")" else "Disabled"}\n")
                    append("• *Registered Owner*: ${settings.telegramPairedUsername ?: "You"}")
                }
                apiClient.sendMessage(token, chatId, statusMsg, parseMode = "Markdown", replyToMessageId = message.messageId)
            }

            lowerText == "/battery" -> {
                val battery = getBatteryInfo()
                apiClient.sendMessage(token, chatId, "🔋 *Battery Status*: $battery", parseMode = "Markdown", replyToMessageId = message.messageId)
            }

            lowerText == "/model" -> {
                val modelInfo = "🧠 Active Provider: *${settings.activeProvider.name}*"
                apiClient.sendMessage(token, chatId, modelInfo, parseMode = "Markdown", replyToMessageId = message.messageId)
            }

            lowerText == "/tools" -> {
                val toolsMsg = buildString {
                    append("🛠️ *Supported EVA Tools*\n\n")
                    append("• *Device*: Flashlight, Volume, Brightness, Alarms, Settings\n")
                    append("• *Apps*: Launch any installed app by name\n")
                    append("• *Calculator*: Math expressions & unit conversions\n")
                    append("• *Contacts*: Search & place phone calls / SMS\n")
                    append("• *Media*: Play/pause, track control\n")
                    append("• *Network*: Wi-Fi & cellular checks\n")
                    append("• *AI*: Multi-step reasoning & general knowledge")
                }
                apiClient.sendMessage(token, chatId, toolsMsg, parseMode = "Markdown", replyToMessageId = message.messageId)
            }

            lowerText == "/unpair" -> {
                preferences.clearTelegramPairing()
                val unpairMsg = buildString {
                    append("🔓 *EVA Successfully Unpaired*\n\n")
                    append("Device ownership has been reset. The *next person* to message this bot will become the new exclusive owner.")
                }
                apiClient.sendMessage(token, chatId, unpairMsg, parseMode = "Markdown", replyToMessageId = message.messageId)
            }

            else -> {
                // Natural language command or question. Execute through CommandDispatcher!
                apiClient.sendChatAction(token, chatId, "typing")

                // Save user message to database
                database.conversationDao().insertMessage(
                    ConversationEntity(
                        role = "user",
                        content = "[Telegram] $text",
                        timestamp = System.currentTimeMillis()
                    )
                )

                try {
                    val result = commandDispatcher.processCommand(text)

                    val replyContent = buildString {
                        val cleanSpoken = result.spokenResponse.replace("💛", "").replace("✨", "").trim()
                        append(cleanSpoken)
                        val toolRes = result.toolResult?.replace("💛", "")?.replace("✨", "")?.trim()
                        if (!toolRes.isNullOrBlank() &&
                            toolRes != cleanSpoken &&
                            !toolRes.equals("Done!", ignoreCase = true) &&
                            !cleanSpoken.contains(toolRes, ignoreCase = true) &&
                            result.toolName != null &&
                            result.toolName != "chat" &&
                            result.toolName != "fast_agent" &&
                            result.toolName != "agent_executor"
                        ) {
                            append("\n\n🛠️ _Tool Result_: ")
                            append(toolRes)
                        }
                    }

                    // Save assistant reply to database
                    database.conversationDao().insertMessage(
                        ConversationEntity(
                            role = "eva",
                            content = replyContent,
                            timestamp = System.currentTimeMillis(),
                            toolName = result.toolName,
                            toolStatus = if (result.isSuccess) "success" else "error"
                        )
                    )

                    apiClient.sendMessage(
                        token = token,
                        chatId = chatId,
                        text = replyContent,
                        parseMode = "Markdown",
                        replyToMessageId = message.messageId
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing command via CommandDispatcher: ${e.message}", e)
                    val errorMsg = "⚠️ *Error processing command*: ${e.message ?: "Unknown error"}"
                    apiClient.sendMessage(token, chatId, errorMsg, parseMode = "Markdown", replyToMessageId = message.messageId)
                }
            }
        }
    }

    private fun getBatteryInfo(): String {
        return try {
            val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, intentFilter)
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

            val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            if (pct >= 0) "$pct%${if (isCharging) " (Charging ⚡)" else ""}" else "Unknown"
        } catch (e: Exception) {
            "Unavailable"
        }
    }
}
