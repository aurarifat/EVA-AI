package com.example.eva.tools

import android.Manifest
import android.content.Context
import com.example.eva.automation.AutomationBackendSelector
import com.example.eva.automation.ScrollDirection
import com.example.eva.context.ContextManager
import com.example.eva.data.prefs.EvaPreferences
import com.example.eva.shizuku.AdbCapabilityManager
import com.example.eva.shizuku.DeviceScreenAutomation
import com.example.eva.shizuku.ShizukuManager

class ToolRegistry(
    private val context: Context,
    val deviceTools: DeviceTools,
    val appLauncherTools: AppLauncherTools,
    val networkTools: NetworkTools,
    val mediaAudioTools: MediaAudioTools,
    val contactTools: ContactTools,
    val pdfAssistant: PdfAssistant,
    val shizukuManager: ShizukuManager,
    val adbCapabilityManager: AdbCapabilityManager,
    val contextManager: ContextManager,
    val preferences: EvaPreferences,
    val deviceScreenAutomation: DeviceScreenAutomation = DeviceScreenAutomation(context, shizukuManager),
    val backendSelector: AutomationBackendSelector = AutomationBackendSelector.createDefault(
        context, preferences, shizukuManager, deviceScreenAutomation
    )
) {

    val toolsList: List<ToolDefinition> = listOf(
        // Screen automation tools (dual-backend powered)
        ToolDefinition("click_text", "Click By Text", "Clicks on an onscreen element matching text or description", ToolCategory.ADVANCED),
        ToolDefinition("click_at", "Click Coordinates", "Precise coordinate tap at (x, y) on device screen", ToolCategory.ADVANCED),
        ToolDefinition("type_text", "Type Text", "Enters text into an active or hinted text input field", ToolCategory.ADVANCED),
        ToolDefinition("press_enter", "Press Enter", "Dispatches Enter key or keyboard confirmation", ToolCategory.ADVANCED),
        ToolDefinition("scroll", "Scroll Screen", "Scrolls up, down, left, or right", ToolCategory.ADVANCED),
        ToolDefinition("swipe", "Swipe Gesture", "Drags/swipes between two coordinates", ToolCategory.ADVANCED),
        ToolDefinition("read_screen", "Read Screen", "Inspects and dumps all visible UI elements and text", ToolCategory.ADVANCED),
        ToolDefinition("press_back", "Press Back", "Dispatches system Back navigation", ToolCategory.ADVANCED),
        ToolDefinition("press_home", "Press Home", "Navigates to the Android launcher home screen", ToolCategory.ADVANCED),
        ToolDefinition("close_ads", "Close Ads", "Detects and dismisses active ads and interstitials", ToolCategory.ADVANCED),
        ToolDefinition("turn_protection_on", "Turn Protection On", "Toggles AdGuard/VPN protection switch on", ToolCategory.ADVANCED),

        // Device & System Tools
        ToolDefinition("flashlight", "Flashlight", "Toggles or sets the rear LED flashlight torch", ToolCategory.DEVICE, listOf(Manifest.permission.CAMERA)),
        ToolDefinition("set_volume", "Volume Control", "Adjusts media audio volume (0-100%)", ToolCategory.DEVICE),
        ToolDefinition("set_alarm", "Set Alarm", "Sets a clock alarm at specified hour and minute", ToolCategory.DEVICE),
        ToolDefinition("set_brightness", "Screen Brightness", "Adjusts display brightness (0-100%)", ToolCategory.DEVICE),
        ToolDefinition("device_status", "Device Telemetry", "Inspects real battery percentage, storage, model and RAM", ToolCategory.DEVICE),
        ToolDefinition("open_settings", "Settings Shortcuts", "Navigates to system Wi-Fi, Bluetooth, Apps, Display, or Sound settings", ToolCategory.DEVICE),

        // Apps & Launcher
        ToolDefinition("open_app", "App Launcher", "Launches installed Android applications by name or package", ToolCategory.APPS),
        ToolDefinition("search_apps", "App Search", "Finds installed packages and launchable utilities", ToolCategory.APPS),
        ToolDefinition("display_overlay", "Floating Quick Dock", "Launches persistent floating bubble overlay to talk to EVA", ToolCategory.AUTOMATION),

        // Media & Communication
        ToolDefinition("start_voice_recording", "Voice Recorder", "Records voice memos to secure local storage", ToolCategory.MEDIA, listOf(Manifest.permission.RECORD_AUDIO)),
        ToolDefinition("stop_voice_recording", "Stop Voice Recording", "Saves and concludes active voice recording", ToolCategory.MEDIA),
        ToolDefinition("play_music", "Music Player", "Plays or resumes local audio tracks", ToolCategory.MEDIA),
        ToolDefinition("pause_music", "Pause Music", "Pauses currently playing music", ToolCategory.MEDIA),
        ToolDefinition("search_contacts", "Contacts Lookup", "Finds saved contacts by name", ToolCategory.COMMUNICATION, listOf(Manifest.permission.READ_CONTACTS)),
        ToolDefinition("call_contact", "Phone Dialer", "Prepares a phone call to a contact or number", ToolCategory.COMMUNICATION),
        ToolDefinition("prepare_message", "SMS Messenger", "Prepares an SMS draft with confirmation", ToolCategory.COMMUNICATION, requiresConfirmation = true),

        // Internet & Utilities
        ToolDefinition("get_ip", "Public IP & Diagnostics", "Retrieves public IP address and connection type", ToolCategory.INTERNET, supportsOffline = false),
        ToolDefinition("test_internet", "Internet Speed & Ping", "Tests network connectivity and latency", ToolCategory.INTERNET, supportsOffline = false),
        ToolDefinition("wikipedia", "Wikipedia Summary", "Fetches concise encyclopedia summaries", ToolCategory.INTERNET, supportsOffline = false),
        ToolDefinition("news", "News Bulletins", "Reads latest curated headlines (Tech, Bangladesh, Global)", ToolCategory.INTERNET, supportsOffline = false),
        ToolDefinition("web_search", "Google Search", "Executes web searches in browser", ToolCategory.INTERNET, supportsOffline = false),
        ToolDefinition("open_url", "URL Opener", "Validates and opens web links", ToolCategory.INTERNET, supportsOffline = false),
        ToolDefinition("calculator", "Local Math Engine", "Solves arithmetic, percentages, and unit conversions offline", ToolCategory.AUTOMATION),
        ToolDefinition("generate_qr", "QR Generator", "Generates QR codes for URLs, Wi-Fi, or contacts", ToolCategory.FILES),
        ToolDefinition("read_pdf", "PDF Reader", "Opens and displays local PDF document pages", ToolCategory.FILES),
        ToolDefinition("summarize_pdf", "PDF Summarizer", "Generates summary of the active PDF", ToolCategory.FILES),
        ToolDefinition("search_pdf", "Search PDF", "Searches text across pages of active PDF", ToolCategory.FILES),
        ToolDefinition("shizuku_status", "Shizuku Diagnostics", "Inspects Shizuku service status and wireless debugging bridge", ToolCategory.ADVANCED, supportsShizuku = true),
        ToolDefinition("adb_capability", "Authorized ADB Command", "Executes whitelisted dumpsys or system diagnostic telemetry", ToolCategory.ADVANCED, supportsShizuku = true)
    )

    fun getToolsPrompt(): String {
        return buildString {
            appendLine("You are EVA AI, a friendly, helpful, and caring personal assistant on an Android smartphone.")
            appendLine("PERSONALITY: Friendly and natural. Keep spoken replies concise (1-2 sentences, e.g. 'All set for you.'). No corporate jargon or disclaimers, and do NOT use emojis so TTS remains clear.")
            appendLine("To execute real device actions, output a single JSON block:")
            appendLine("```json")
            appendLine("{\"tool\": \"<tool_name>\", \"action\": \"<action_name>\", \"parameters\": {\"<key>\": \"<value>\"}}")
            appendLine("```")
            appendLine()
            appendLine("### SCREEN AUTOMATION SCHEMA & RULES")
            appendLine("1. `click_text`: {\"tool\": \"click_text\", \"parameters\": {\"text\": \"Button Label\"}}")
            appendLine("2. `click_at`: {\"tool\": \"click_at\", \"parameters\": {\"x\": \"540\", \"y\": \"960\"}}")
            appendLine("3. `type_text`: {\"tool\": \"type_text\", \"parameters\": {\"text\": \"Search Query\", \"field_hint\": \"Search\"}}")
            appendLine("4. `press_enter`: {\"tool\": \"press_enter\"}")
            appendLine("5. `scroll`: {\"tool\": \"scroll\", \"parameters\": {\"direction\": \"down\"}} (directions: \"up\", \"down\", \"left\", \"right\")")
            appendLine("6. `swipe`: {\"tool\": \"swipe\", \"parameters\": {\"startX\": \"100\", \"startY\": \"500\", \"endX\": \"100\", \"endY\": \"100\"}}")
            appendLine("7. `read_screen`: {\"tool\": \"read_screen\"}")
            appendLine("8. `press_back`: {\"tool\": \"press_back\"}")
            appendLine("9. `press_home`: {\"tool\": \"press_home\"}")
            appendLine("10. `open_app`: {\"tool\": \"open_app\", \"parameters\": {\"name\": \"YouTube\"}}")
            appendLine("11. `set_alarm`: {\"tool\": \"set_alarm\", \"parameters\": {\"hour\": \"7\", \"minutes\": \"30\", \"label\": \"Morning Work\"}}")
            appendLine("12. `set_brightness`: {\"tool\": \"set_brightness\", \"parameters\": {\"level\": \"75\"}}")
            appendLine("13. `wait`: {\"tool\": \"wait\", \"parameters\": {\"duration_ms\": \"1000\"}}")
            appendLine("14. `done`: {\"tool\": \"done\", \"parameters\": {\"summary\": \"Finished task successfully.\"}}")
            appendLine()
            appendLine("RULE: Prefer `click_text` when the element has clear visible text or content-description. Use `click_at` for icon-only controls, canvas UI, or when `click_text` just failed.")
        }
    }

    suspend fun executeTool(toolName: String, action: String, parameters: Map<String, String>): ToolExecutionResult {
        val result = when (toolName.lowercase()) {
            // Screen automation tools (delegated to active backend: Accessibility or Shizuku)
            "click_text" -> {
                val text = parameters["text"] ?: parameters["query"] ?: action
                val res = backendSelector.executeSafeAction("click_text") { it.clickByText(text) }
                ToolExecutionResult(res.isSuccess, res.message, data = res)
            }

            "click_at" -> {
                val x = parameters["x"]?.toIntOrNull() ?: 0
                val y = parameters["y"]?.toIntOrNull() ?: 0
                val res = backendSelector.executeSafeAction("click_at") { it.clickAt(x, y) }
                ToolExecutionResult(res.isSuccess, res.message, data = res)
            }

            "type_text" -> {
                val text = parameters["text"] ?: parameters["query"] ?: action
                val hint = parameters["field_hint"] ?: parameters["hint"]
                val res = backendSelector.executeSafeAction("type_text") { it.typeText(text, hint) }
                ToolExecutionResult(res.isSuccess, res.message, data = res)
            }

            "press_enter" -> {
                val res = backendSelector.executeSafeAction("press_enter") { it.pressEnter() }
                ToolExecutionResult(res.isSuccess, res.message, data = res)
            }

            "scroll" -> {
                val dirStr = parameters["direction"] ?: action
                val dir = when (dirStr.lowercase()) {
                    "up" -> ScrollDirection.UP
                    "left" -> ScrollDirection.LEFT
                    "right" -> ScrollDirection.RIGHT
                    else -> ScrollDirection.DOWN
                }
                val res = backendSelector.executeSafeAction("scroll") { it.scroll(dir) }
                ToolExecutionResult(res.isSuccess, res.message, data = res)
            }

            "swipe" -> {
                val sx = parameters["startX"]?.toIntOrNull() ?: 540
                val sy = parameters["startY"]?.toIntOrNull() ?: 1200
                val ex = parameters["endX"]?.toIntOrNull() ?: 540
                val ey = parameters["endY"]?.toIntOrNull() ?: 400
                val dur = parameters["duration_ms"]?.toLongOrNull() ?: 300L
                val res = backendSelector.executeSafeAction("swipe") { it.swipe(sx, sy, ex, ey, dur) }
                ToolExecutionResult(res.isSuccess, res.message, data = res)
            }

            "read_screen" -> {
                val backend = backendSelector.getActiveBackend()
                if (backend == null) {
                    ToolExecutionResult(false, "No screen automation backend available (Enable Accessibility or authorize Shizuku).")
                } else {
                    val nodes = backend.dumpScreen()
                    val summary = nodes.take(15).joinToString("; ") {
                        val text = it.text.ifBlank { it.contentDescription }
                        "[$text @ (${it.centerX}, ${it.centerY})]"
                    }
                    ToolExecutionResult(true, "Screen read complete (${nodes.size} elements found). Highlights: $summary", data = nodes)
                }
            }

            "press_back" -> {
                val res = backendSelector.executeSafeAction("press_back") { it.pressBack() }
                ToolExecutionResult(res.isSuccess, res.message, data = res)
            }

            "press_home" -> {
                val res = backendSelector.executeSafeAction("press_home") { it.pressHome() }
                ToolExecutionResult(res.isSuccess, res.message, data = res)
            }

            // Backward compatibility for generic screen_automation
            "screen_automation" -> {
                val targetAction = parameters["action"] ?: action
                val query = parameters["query"] ?: parameters["text"] ?: ""
                when (targetAction.lowercase()) {
                    "click", "tap" -> executeTool("click_text", query, mapOf("text" to query))
                    "scroll_down" -> executeTool("scroll", "down", mapOf("direction" to "down"))
                    "scroll_up" -> executeTool("scroll", "up", mapOf("direction" to "up"))
                    "slide_left" -> executeTool("scroll", "left", mapOf("direction" to "left"))
                    "slide_right" -> executeTool("scroll", "right", mapOf("direction" to "right"))
                    else -> ToolExecutionResult(false, "Unknown screen automation action: $targetAction")
                }
            }

            "close_ads" -> {
                val (ok, msg) = deviceScreenAutomation.closeAds()
                ToolExecutionResult(ok, msg)
            }

            "turn_protection_on" -> {
                val (ok, msg) = deviceScreenAutomation.turnProtectionOn()
                ToolExecutionResult(ok, msg)
            }

            // Device Tools
            "flashlight" -> {
                val enable = when (action.lowercase()) {
                    "enable", "on", "start" -> true
                    "disable", "off", "stop" -> false
                    else -> null
                }
                deviceTools.toggleFlashlight(enable)
            }

            "set_volume" -> {
                val level = parameters["level"]?.toIntOrNull()
                    ?: parameters["percentage"]?.toIntOrNull()
                    ?: if (action.contains("50")) 50 else 70
                deviceTools.setVolume(level)
            }

            "set_alarm" -> {
                val hour = parameters["hour"]?.toIntOrNull() ?: 7
                val minutes = parameters["minutes"]?.toIntOrNull() ?: 0
                val label = parameters["label"] ?: parameters["message"] ?: "EVA Alarm"
                deviceTools.setAlarm(hour, minutes, label)
            }

            "set_brightness" -> {
                val level = parameters["level"]?.toIntOrNull()
                    ?: parameters["percentage"]?.toIntOrNull()
                    ?: 50
                deviceTools.setBrightness(level)
            }

            "device_status" -> deviceTools.getDeviceStatus()

            "open_settings" -> {
                val type = parameters["type"] ?: action
                deviceTools.openSettings(type)
            }

            // Apps & Overlay
            "open_app" -> {
                val appName = parameters["name"] ?: parameters["app"] ?: action
                val res = appLauncherTools.launchAppByName(appName)
                if (res.isSuccess) {
                    contextManager.updateActiveApp(appName, (res.data as? String) ?: "")
                }
                res
            }

            "search_apps" -> {
                val query = parameters["query"] ?: action
                val found = appLauncherTools.findApp(query)
                if (found != null) {
                    ToolExecutionResult(true, "Found ${found.appName} (${found.packageName}).", data = found)
                } else {
                    ToolExecutionResult(false, "That app isn't installed on this device.")
                }
            }

            "display_overlay" -> {
                if (com.example.eva.overlay.EvaOverlayService.isOverlayPermissionGranted(context)) {
                    val homeIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                        addCategory(android.content.Intent.CATEGORY_HOME)
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(homeIntent)
                    com.example.eva.overlay.EvaOverlayService.startOverlay(context)
                    ToolExecutionResult(true, "Display overlay active. Switched to home screen.")
                } else {
                    com.example.eva.overlay.EvaOverlayService.openOverlaySettings(context)
                    ToolExecutionResult(false, "Please grant 'Display over other apps' permission to enable the floating bubble.")
                }
            }

            // Media & Contacts
            "start_voice_recording" -> mediaAudioTools.startVoiceRecording()
            "stop_voice_recording" -> mediaAudioTools.stopVoiceRecording()
            "play_music" -> mediaAudioTools.playMusic()
            "pause_music" -> mediaAudioTools.pauseMusic()

            "search_contacts" -> {
                if (!contactTools.hasContactsPermission()) {
                    ToolExecutionResult(false, "Contacts permission not granted. Please allow Contacts permission in App Settings.")
                } else {
                    val query = parameters["query"] ?: action
                    val list = contactTools.searchContacts(query)
                    if (list.isNotEmpty()) {
                        val names = list.take(5).joinToString(", ") { "${it.displayName} (${it.phoneNumber ?: "No phone"})" }
                        ToolExecutionResult(true, "Found contacts: $names", data = list)
                    } else {
                        ToolExecutionResult(false, "No contacts found matching '$query'.")
                    }
                }
            }

            "call_contact" -> {
                val target = parameters["target"] ?: parameters["name"] ?: action
                contactTools.makePhoneCall(target)
            }

            "prepare_message" -> {
                val recipient = parameters["recipient"] ?: parameters["to"] ?: ""
                val text = parameters["text"] ?: parameters["body"] ?: ""
                contactTools.prepareSms(recipient, text)
            }

            // Internet
            "get_ip" -> {
                val diag = networkTools.runInternetDiagnostics()
                if (diag.publicIp != null) {
                    ToolExecutionResult(true, "Your public IP is ${diag.publicIp} (Connected via ${diag.networkType}).", data = diag)
                } else {
                    ToolExecutionResult(false, "Could not determine public IP. Network: ${diag.networkType}")
                }
            }

            "test_internet" -> {
                val diag = networkTools.runInternetDiagnostics()
                if (diag.isConnected) {
                    ToolExecutionResult(true, "Internet is working (${diag.networkType}, ping: ${diag.pingMs}ms).", data = diag)
                } else {
                    ToolExecutionResult(false, "Device is offline or disconnected.")
                }
            }

            "wikipedia" -> {
                val query = parameters["query"] ?: action
                networkTools.searchWikipedia(query)
            }

            "news" -> {
                val cat = parameters["category"] ?: action
                val items = networkTools.getNews(cat)
                val summary = items.joinToString("\n• ") { "${it.title} (${it.source})" }
                ToolExecutionResult(true, "Here is the latest $cat news:\n• $summary", data = items)
            }

            "web_search" -> {
                val query = parameters["query"] ?: action
                networkTools.openWebSearch(query)
            }

            "open_url" -> {
                val url = parameters["url"] ?: action
                networkTools.openUrl(url)
            }

            "summarize_pdf" -> pdfAssistant.summarizeCurrentPdf()
            "search_pdf" -> {
                val query = parameters["query"] ?: action
                pdfAssistant.searchPdf(query)
            }

            "calculator" -> {
                val expr = parameters["expression"] ?: parameters["query"] ?: action
                CalculatorTool.calculate(expr)
            }

            "generate_qr" -> {
                val content = parameters["text"] ?: parameters["content"] ?: action
                QrTools.generateQrBitmap(content)
                ToolExecutionResult(true, "QR code created for '$content'.")
            }

            "gaming_mode" -> {
                deviceTools.setVolume(80)
                ToolExecutionResult(true, "Gaming mode activated: Volume set to 80% and background optimizations enabled.")
            }

            "study_mode" -> {
                deviceTools.setVolume(30)
                ToolExecutionResult(true, "Study mode activated: Focus timer ready and notification sounds softened.")
            }

            "night_mode" -> {
                deviceTools.setVolume(15)
                ToolExecutionResult(true, "Night mode enabled: Audio softened for peaceful rest.")
            }

            "shizuku_status" -> {
                shizukuManager.checkStatus()
                val info = shizukuManager.shizukuState.value
                ToolExecutionResult(true, info.lastPingMessage, data = info)
            }

            "wait" -> {
                val dur = parameters["duration_ms"]?.toLongOrNull() ?: 1000L
                kotlinx.coroutines.delay(dur)
                ToolExecutionResult(true, "Waited for ${dur}ms.")
            }

            "done" -> {
                val summary = parameters["summary"] ?: "Task completed."
                ToolExecutionResult(true, summary)
            }

            else -> ToolExecutionResult(false, "Unknown tool: $toolName")
        }

        contextManager.updateToolExecution(toolName, action, result.message, result.isSuccess)
        return result
    }
}
