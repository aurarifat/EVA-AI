package com.example.eva.automation

import android.content.Context
import android.util.Log
import com.example.eva.data.prefs.EvaPreferences
import com.example.eva.shizuku.DeviceScreenAutomation
import com.example.eva.shizuku.ShizukuManager

enum class ActiveAutomationBackendType(val label: String) {
    ACCESSIBILITY("Accessibility Engine"),
    SHIZUKU("Shizuku (Fallback)"),
    NONE("None (Disabled)")
}

class AutomationBackendSelector(
    private val context: Context,
    val accessibilityBackend: AccessibilityBackend,
    val shizukuBackend: ShizukuBackend
) {

    fun getActiveBackendType(): ActiveAutomationBackendType {
        return when {
            accessibilityBackend.isAvailable() -> ActiveAutomationBackendType.ACCESSIBILITY
            shizukuBackend.isAvailable() -> ActiveAutomationBackendType.SHIZUKU
            else -> ActiveAutomationBackendType.NONE
        }
    }

    fun getActiveBackend(): ScreenAutomationBackend? {
        return when {
            accessibilityBackend.isAvailable() -> accessibilityBackend
            shizukuBackend.isAvailable() -> shizukuBackend
            else -> null
        }
    }

    fun isAnyBackendAvailable(): Boolean {
        return getActiveBackend() != null
    }

    suspend fun executeSafeAction(
        actionName: String,
        block: suspend (ScreenAutomationBackend) -> AutomationResult
    ): AutomationResult {
        val backend = getActiveBackend()
        if (backend == null) {
            Log.e("AutomationBackendSelector", "Action '$actionName' failed: No automation backend available.")
            return AutomationResult(
                isSuccess = false,
                message = "No automation backend available. Please enable the EVA Accessibility Service in Android Settings, or authorize Shizuku."
            )
        }
        return block(backend)
    }

    companion object {
        fun createDefault(
            context: Context,
            preferences: EvaPreferences,
            shizukuManager: ShizukuManager,
            deviceScreenAutomation: DeviceScreenAutomation
        ): AutomationBackendSelector {
            val a11y = AccessibilityBackend(context, preferences)
            val shizuku = ShizukuBackend(context, deviceScreenAutomation, shizukuManager, preferences)
            return AutomationBackendSelector(context, a11y, shizuku)
        }
    }
}
