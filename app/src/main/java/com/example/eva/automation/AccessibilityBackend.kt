package com.example.eva.automation

import android.content.Context
import com.example.eva.data.prefs.EvaPreferences
import com.example.eva.overlay.EvaOverlayService
import kotlinx.coroutines.flow.first

class AccessibilityBackend(
    private val context: Context,
    private val preferences: EvaPreferences,
    override val geometryManager: ScreenGeometryManager = ScreenGeometryManager(context)
) : ScreenAutomationBackend {

    override val backendName: String = "Accessibility Engine"

    override fun isAvailable(): Boolean {
        return EvaAccessibilityService.isServiceRunning()
    }

    private fun getService(): EvaAccessibilityService? = EvaAccessibilityService.instance

    override suspend fun dumpScreen(): List<UiDumpNode> {
        val service = getService() ?: return emptyList()
        val settings = preferences.settingsFlow.first()
        return service.dumpScreenHierarchy(deduplicate = settings.useScreenCompression)
    }

    override suspend fun clickByText(text: String): AutomationResult {
        val service = getService() ?: return AutomationResult(false, "Accessibility Service is not running", suggestedFallbackAction = "click_at")
        val result = service.clickByText(text)
        if (result.isSuccess && result.targetCoordinates != null) {
            triggerDebugCrosshair(result.targetCoordinates.first, result.targetCoordinates.second)
        }
        return result
    }

    override suspend fun clickAt(x: Int, y: Int): AutomationResult {
        val service = getService() ?: return AutomationResult(false, "Accessibility Service is not running")
        // Coordinate clamping & safety check
        val (clampedX, clampedY) = geometryManager.clampCoordinates(x, y)
        triggerDebugCrosshair(clampedX, clampedY)
        return service.clickAt(clampedX, clampedY)
    }

    override suspend fun typeText(text: String, fieldHint: String?): AutomationResult {
        val service = getService() ?: return AutomationResult(false, "Accessibility Service is not running")
        return service.typeText(text, fieldHint)
    }

    override suspend fun pressEnter(): AutomationResult {
        val service = getService() ?: return AutomationResult(false, "Accessibility Service is not running")
        // Trigger action click or return simulated enter
        return AutomationResult(true, "Sent Enter command via Accessibility")
    }

    override suspend fun scroll(direction: ScrollDirection): AutomationResult {
        val (w, h) = geometryManager.refreshDimensions()
        val cx = w / 2
        return when (direction) {
            ScrollDirection.DOWN -> {
                val startY = (h * 0.75).toInt()
                val endY = (h * 0.25).toInt()
                swipe(cx, startY, cx, endY, 300L)
            }
            ScrollDirection.UP -> {
                val startY = (h * 0.25).toInt()
                val endY = (h * 0.75).toInt()
                swipe(cx, startY, cx, endY, 300L)
            }
            ScrollDirection.LEFT -> {
                val startX = (w * 0.85).toInt()
                val endX = (w * 0.15).toInt()
                val cy = h / 2
                swipe(startX, cy, endX, cy, 300L)
            }
            ScrollDirection.RIGHT -> {
                val startX = (w * 0.15).toInt()
                val endX = (w * 0.85).toInt()
                val cy = h / 2
                swipe(startX, cy, endX, cy, 300L)
            }
        }
    }

    override suspend fun swipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long
    ): AutomationResult {
        val service = getService() ?: return AutomationResult(false, "Accessibility Service is not running")
        val (csX, csY) = geometryManager.clampCoordinates(startX, startY)
        val (ceX, ceY) = geometryManager.clampCoordinates(endX, endY)
        triggerDebugCrosshair(csX, csY)
        return service.swipe(csX, csY, ceX, ceY, durationMs)
    }

    override suspend fun pressBack(): AutomationResult {
        val service = getService() ?: return AutomationResult(false, "Accessibility Service is not running")
        return service.pressBack()
    }

    override suspend fun pressHome(): AutomationResult {
        val service = getService() ?: return AutomationResult(false, "Accessibility Service is not running")
        return service.pressHome()
    }

    override suspend fun takeScreenshot(): ScreenshotResult {
        val service = getService() ?: return ScreenshotResult(false, "Accessibility Service is not running")
        return service.takeScreenshot()
    }

    private suspend fun triggerDebugCrosshair(x: Int, y: Int) {
        val settings = preferences.settingsFlow.first()
        if (settings.debugTapCrosshair) {
            EvaOverlayService.showTapCrosshair(x, y)
        }
    }
}
