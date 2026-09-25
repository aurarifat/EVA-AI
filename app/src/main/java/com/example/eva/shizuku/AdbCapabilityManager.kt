package com.example.eva.shizuku

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

data class AdbCapability(
    val id: String,
    val toolName: String,
    val description: String,
    val command: Array<String>,
    val requiresConfirmation: Boolean = false,
    val requiredPermission: String = "moe.shizuku.privileged.api.permission"
)

data class AdbExecutionResult(
    val isSuccess: Boolean,
    val output: String,
    val exitCode: Int,
    val executionTimeMs: Long
)

class AdbCapabilityManager(
    private val context: Context,
    private val shizukuManager: ShizukuManager
) {

    // Strictly whitelisted commands ONLY. Zero arbitrary command execution!
    private val whitelist = mapOf(
        "dumpsys_battery" to AdbCapability(
            id = "dumpsys_battery",
            toolName = "Battery Diagnostics",
            description = "Reads detailed hardware battery levels, temperature, and voltage via dumpsys",
            command = arrayOf("dumpsys", "battery")
        ),
        "dumpsys_meminfo" to AdbCapability(
            id = "dumpsys_meminfo",
            toolName = "Memory Diagnostics",
            description = "Reads hardware RAM usage and kernel memory breakdown",
            command = arrayOf("dumpsys", "meminfo", "--compact")
        ),
        "system_uptime" to AdbCapability(
            id = "system_uptime",
            toolName = "System Uptime",
            description = "Reads device uptime and kernel load stats",
            command = arrayOf("uptime")
        ),
        "getprop_model" to AdbCapability(
            id = "getprop_model",
            toolName = "System Hardware Properties",
            description = "Reads hardware model and build fingerprint",
            command = arrayOf("getprop", "ro.product.model")
        )
    )

    fun getWhitelistedCapabilities(): List<AdbCapability> = whitelist.values.toList()

    fun isAdbAvailable(): Boolean {
        val info = shizukuManager.shizukuState.value
        return info.isBinderAlive && info.isAuthorized
    }

    suspend fun executeWhitelistedCapability(capabilityId: String): AdbExecutionResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        if (!isAdbAvailable()) {
            return@withContext AdbExecutionResult(
                isSuccess = false,
                output = "ADB control isn't currently available. Shizuku authorization is required.",
                exitCode = -1,
                executionTimeMs = 0L
            )
        }

        val capability = whitelist[capabilityId]
        if (capability == null) {
            return@withContext AdbExecutionResult(
                isSuccess = false,
                output = "Forbidden action: Operation '$capabilityId' is not on the authorized capability whitelist.",
                exitCode = -2,
                executionTimeMs = 0L
            )
        }

        try {
            // Use official Shizuku newProcess for authorized shell execution
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
            val process = method.invoke(null, capability.command, null, null) as Process
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val outputBuilder = StringBuilder()
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                outputBuilder.appendLine(line)
            }

            val exitCode = process.waitFor()
            val latency = System.currentTimeMillis() - startTime

            AdbExecutionResult(
                isSuccess = exitCode == 0,
                output = outputBuilder.toString().trim().ifBlank { "Executed with exit code $exitCode" },
                exitCode = exitCode,
                executionTimeMs = latency
            )
        } catch (e: Exception) {
            AdbExecutionResult(
                isSuccess = false,
                output = "ADB execution error: ${e.localizedMessage}",
                exitCode = -3,
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }
}
