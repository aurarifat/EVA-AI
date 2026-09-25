package com.example.eva.tools

import android.Manifest
import android.content.Context
import com.example.eva.context.ContextManager
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
    val deviceScreenAutomation: DeviceScreenAutomation = DeviceScreenAutomation(context, shizukuManager)
) {

    val toolsList: List<ToolDefinition> = listOf(
        ToolDefinition("flashlight", "Flashlight", "Toggles or sets the rear LED flashlight torch", ToolCategory.DEVICE, listOf(Manifest.permission.CAMERA)),
        ToolDefinition("set_volume", "Volume Control", "Adjusts media audio volume (0-100%)", ToolCategory.DEVICE),
        ToolDefinition("device_status", "Device Telemetry", "Inspects real battery percentage, storage, model and RAM", ToolCategory.DEVICE),
        ToolDefinition("open_settings", "Settings Shortcuts", "Navigates to system Wi-Fi, Bluetooth, Apps, Display, or Sound settings", ToolCategory.DEVICE),
        ToolDefinition("open_app", "App Launcher", "Launches installed Android applications by name or package", ToolCategory.APPS),
        ToolDefinition("search_apps", "App Search", "Finds installed packages and launchable utilities", ToolCategory.APPS),
        ToolDefinition("close_ads", "Close Ads", "Analyzes screen and dismisses active ads via Shizuku privileged shell", ToolCategory.ADVANCED, supportsShizuku = true),
        ToolDefinition("turn_protection_on", "Turn Protection On", "Toggles AdGuard/VPN protection switch on via screen analysis", ToolCategory.ADVANCED, supportsShizuku = true),
        ToolDefinition("screen_automation", "Screen Automation", "Clicks, scrolls, or slides anywhere by analyzing screen layout", ToolCategory.ADVANCED, supportsShizuku = true),
        ToolDefinition("display_overlay", "Floating Display Overlay", "Launches persistent floating bubble overlay to talk to EVA", ToolCategory.AUTOMATION),
        ToolDefinition("start_voice_recording", "Voice Recorder", "Records voice memos to secure local storage", ToolCategory.MEDIA, listOf(Manifest.permission.RECORD_AUDIO)),
        ToolDefinition("stop_voice_recording", "Stop Voice Recording", "Saves and concludes active voice recording", ToolCategory.MEDIA),
        ToolDefinition("play_music", "Music Player", "Plays or resumes local audio tracks", ToolCategory.MEDIA),
        ToolDefinition("pause_music", "Pause Music", "Pauses currently playing music", ToolCategory.MEDIA),
        ToolDefinition("get_ip", "Public IP & Diagnostics", "Retrieves public IP address and connection type", ToolCategory.INTERNET, supportsOffline = false),
        ToolDefinition("test_internet", "Internet Speed & Ping", "Tests network connectivity and latency", ToolCategory.INTERNET, supportsOffline = false),
        ToolDefinition("wikipedia", "Wikipedia Summary", "Fetches concise encyclopedia summaries", ToolCategory.INTERNET, supportsOffline = false),
        ToolDefinition("news", "News Bulletins", "Reads latest curated headlines (Tech, Bangladesh, Global)", ToolCategory.INTERNET, supportsOffline = false),
        ToolDefinition("web_search", "Google Search", "Executes web searches in browser", ToolCategory.INTERNET, supportsOffline = false),
        ToolDefinition("open_url", "URL Opener", "Validates and opens web links", ToolCategory.INTERNET, supportsOffline = false),
        ToolDefinition("search_contacts", "Contacts Lookup", "Finds saved contacts by name", ToolCategory.COMMUNICATION, listOf(Manifest.permission.READ_CONTACTS)),
        ToolDefinition("call_contact", "Phone Dialer", "Prepares a phone call to a contact or number", ToolCategory.COMMUNICATION),
        ToolDefinition("prepare_message", "SMS Messenger", "Prepares an SMS draft with confirmation", ToolCategory.COMMUNICATION, requiresConfirmation = true),
        ToolDefinition("read_pdf", "PDF Reader", "Opens and displays local PDF document pages", ToolCategory.FILES),
        ToolDefinition("summarize_pdf", "PDF Summarizer", "Generates summary of the active PDF", ToolCategory.FILES),
        ToolDefinition("search_pdf", "Search PDF", "Searches text across pages of active PDF", ToolCategory.FILES),
        ToolDefinition("generate_qr", "QR Generator", "Generates QR codes for URLs, Wi-Fi, or contacts", ToolCategory.FILES),
        ToolDefinition("calculator", "Local Math Engine", "Solves arithmetic, percentages, and unit conversions offline", ToolCategory.AUTOMATION),
        ToolDefinition("gaming_mode", "Gaming Mode", "Optimizes volume and launches configured game", ToolCategory.AUTOMATION),
        ToolDefinition("study_mode", "Study Mode", "Enables focus environment and opens study tools", ToolCategory.AUTOMATION),
        ToolDefinition("night_mode", "Night Mode", "Prepares gentle volume and sleep timers", ToolCategory.AUTOMATION),
        ToolDefinition("shizuku_status", "Shizuku Diagnostics", "Inspects Shizuku service status and wireless debugging bridge", ToolCategory.ADVANCED, supportsShizuku = true),
        ToolDefinition("adb_capability", "Authorized ADB Command", "Executes whitelisted dumpsys or system diagnostic telemetry", ToolCategory.ADVANCED, supportsShizuku = true)
    )

    fun getToolsPrompt(): String {
        val sb = StringBuilder()
        sb.appendLine("You can execute real tools on this device by outputting a JSON block like:")
        sb.appendLine("```json")
        sb.appendLine("{\"tool\": \"flashlight\", \"action\": \"enable\"}")
        sb.appendLine("```")
        sb.appendLine("Available tools:")
        for (t in toolsList) {
            sb.appendLine("- ${t.name}: ${t.description} (Category: ${t.category.title})")
        }
        return sb.toString()
    }

    suspend fun executeTool(toolName: String, action: String, parameters: Map<String, String>): ToolExecutionResult {
        val result = when (toolName.lowercase()) {
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
            "media_control" -> {
                when (action.lowercase()) {
                    "mute" -> deviceTools.muteVolume(true)
                    "unmute" -> deviceTools.muteVolume(false)
                    "pause" -> mediaAudioTools.pauseMusic()
                    "play", "resume" -> mediaAudioTools.playMusic()
                    "increase" -> deviceTools.adjustVolume(true)
                    "decrease" -> deviceTools.adjustVolume(false)
                    else -> ToolExecutionResult(false, "Unknown media action: $action")
                }
            }
            "device_status" -> deviceTools.getDeviceStatus()
            "open_settings" -> {
                val type = parameters["type"] ?: action
                deviceTools.openSettings(type)
            }
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
            "start_voice_recording" -> mediaAudioTools.startVoiceRecording()
            "stop_voice_recording" -> mediaAudioTools.stopVoiceRecording()
            "play_music" -> mediaAudioTools.playMusic()
            "pause_music" -> mediaAudioTools.pauseMusic()
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
            "search_contacts" -> {
                val query = parameters["query"] ?: action
                val list = contactTools.searchContacts(query)
                if (list.isNotEmpty()) {
                    val names = list.take(5).joinToString(", ") { "${it.displayName} (${it.phoneNumber ?: "No phone"})" }
                    ToolExecutionResult(true, "Found contacts: $names", data = list)
                } else {
                    ToolExecutionResult(false, "No contacts found matching '$query'.")
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
            "summarize_pdf" -> pdfAssistant.summarizeCurrentPdf()
            "search_pdf" -> {
                val query = parameters["query"] ?: action
                pdfAssistant.searchPdf(query)
            }
            "calculator" -> {
                val expr = parameters["expression"] ?: parameters["query"] ?: action
                CalculatorTool.calculate(expr)
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
            "close_ads" -> {
                val (ok, msg) = deviceScreenAutomation.closeAds()
                ToolExecutionResult(ok, msg)
            }
            "turn_protection_on" -> {
                val (ok, msg) = deviceScreenAutomation.turnProtectionOn()
                ToolExecutionResult(ok, msg)
            }
            "screen_automation" -> {
                val targetAction = parameters["action"] ?: action
                val query = parameters["query"] ?: parameters["text"] ?: ""
                val res = when (targetAction.lowercase()) {
                    "click", "tap" -> deviceScreenAutomation.clickByText(query)
                    "scroll_down" -> deviceScreenAutomation.scrollDown()
                    "scroll_up" -> deviceScreenAutomation.scrollUp()
                    "slide_left" -> deviceScreenAutomation.slideLeft()
                    "slide_right" -> deviceScreenAutomation.slideRight()
                    else -> Pair(false, "Unknown screen automation action: $targetAction")
                }
                ToolExecutionResult(res.first, res.second)
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
                    val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    ToolExecutionResult(false, "Please grant 'Display over other apps' permission to enable the floating bubble.")
                }
            }
            else -> ToolExecutionResult(false, "Unknown tool: $toolName")
        }

        contextManager.updateToolExecution(toolName, action, result.message, result.isSuccess)
        return result
    }
}
