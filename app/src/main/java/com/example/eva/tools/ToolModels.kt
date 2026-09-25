package com.example.eva.tools

enum class ToolCategory(val title: String) {
    DEVICE("Device Control"),
    APPS("App Launcher"),
    MEDIA("Media & Audio"),
    COMMUNICATION("Communication"),
    INTERNET("Internet & Information"),
    FILES("Files & Documents"),
    AUTOMATION("Automation & Modes"),
    ADVANCED("Shizuku & ADB")
}

data class ToolDefinition(
    val name: String,
    val displayName: String,
    val description: String,
    val category: ToolCategory,
    val requiredPermissions: List<String> = emptyList(),
    val requiresConfirmation: Boolean = false,
    val supportsOffline: Boolean = true,
    val supportsShizuku: Boolean = false
)

data class ToolExecutionResult(
    val isSuccess: Boolean,
    val message: String,
    val data: Any? = null,
    val requiresUserConfirmation: Boolean = false,
    val confirmationPrompt: String? = null
)
