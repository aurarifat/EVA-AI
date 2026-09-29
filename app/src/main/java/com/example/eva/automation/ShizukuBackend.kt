package com.example.eva.automation

import android.content.Context
import android.graphics.Rect
import com.example.eva.data.prefs.EvaPreferences
import com.example.eva.overlay.EvaOverlayService
import com.example.eva.shizuku.DeviceScreenAutomation
import com.example.eva.shizuku.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class ShizukuBackend(
    private val context: Context,
    val deviceScreenAutomation: DeviceScreenAutomation,
    val shizukuManager: ShizukuManager,
    private val preferences: EvaPreferences,
    override val geometryManager: ScreenGeometryManager = ScreenGeometryManager(context)
) : ScreenAutomationBackend {

    override val backendName: String = "Shizuku Shell (Fallback)"

    override fun isAvailable(): Boolean {
        return shizukuManager.shizukuState.value.isAuthorized
    }

    override suspend fun dumpScreen(): List<UiDumpNode> = withContext(Dispatchers.IO) {
        val legacyNodes = deviceScreenAutomation.dumpScreenHierarchy()
        val settings = preferences.settingsFlow.first()

        val converted = legacyNodes.map { n ->
            UiDumpNode(
                text = n.text,
                contentDescription = n.contentDescription,
                resourceId = n.resourceId,
                className = n.className,
                packageName = n.packageName,
                bounds = n.bounds,
                isClickable = n.clickable,
                isEditable = n.className.contains("Edit", ignoreCase = true) || n.className.contains("Text", ignoreCase = true),
                isScrollable = n.className.contains("Scroll", ignoreCase = true) || n.className.contains("Recycler", ignoreCase = true) || n.className.contains("List", ignoreCase = true),
                isChecked = n.checked,
                isEnabled = n.enabled,
                isPassword = n.resourceId.contains("password", ignoreCase = true)
            )
        }

        if (settings.useScreenCompression) {
            val seen = mutableSetOf<String>()
            converted.filter { node ->
                val key = "${node.bounds.toShortString()}_${node.className}_${node.text}"
                seen.add(key)
            }
        } else {
            converted
        }
    }

    override suspend fun clickByText(text: String): AutomationResult {
        val (ok, msg) = deviceScreenAutomation.clickByText(text)
        return AutomationResult(
            isSuccess = ok,
            message = msg,
            suggestedFallbackAction = if (!ok) "click_at" else null
        )
    }

    override suspend fun clickAt(x: Int, y: Int): AutomationResult {
        val (clampedX, clampedY) = geometryManager.clampCoordinates(x, y)
        triggerDebugCrosshair(clampedX, clampedY)
        val (ok, msg) = deviceScreenAutomation.tap(clampedX, clampedY)
        return AutomationResult(ok, msg, targetCoordinates = Pair(clampedX, clampedY))
    }

    override suspend fun typeText(text: String, fieldHint: String?): AutomationResult = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext AutomationResult(false, "Shizuku not authorized")
        // Sanitize string for adb input text: spaces encoded as %s
        val escaped = text.replace(" ", "%s").replace("'", "\\'").replace("\"", "\\\"")
        val (code, out) = shizukuManager.executeRawCommand(arrayOf("input", "text", escaped))
        if (code == 0) {
            AutomationResult(true, "Typed text via Shizuku shell.")
        } else {
            AutomationResult(false, "Failed to type text: $out")
        }
    }

    override suspend fun pressEnter(): AutomationResult = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext AutomationResult(false, "Shizuku not authorized")
        val (code, out) = shizukuManager.executeRawCommand(arrayOf("input", "keyevent", "66")) // KEYCODE_ENTER
        AutomationResult(code == 0, if (code == 0) "Pressed Enter." else out)
    }

    override suspend fun scroll(direction: ScrollDirection): AutomationResult = withContext(Dispatchers.IO) {
        val (w, h) = geometryManager.refreshDimensions()
        val cx = w / 2
        return@withContext when (direction) {
            ScrollDirection.DOWN -> {
                val startY = (h * 0.70).toInt()
                val endY = (h * 0.25).toInt()
                swipe(cx, startY, cx, endY, 300L)
            }
            ScrollDirection.UP -> {
                val startY = (h * 0.25).toInt()
                val endY = (h * 0.70).toInt()
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
    ): AutomationResult = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext AutomationResult(false, "Shizuku not authorized")
        val (csX, csY) = geometryManager.clampCoordinates(startX, startY)
        val (ceX, ceY) = geometryManager.clampCoordinates(endX, endY)
        triggerDebugCrosshair(csX, csY)
        val (code, out) = shizukuManager.executeRawCommand(
            arrayOf("input", "swipe", "$csX", "$csY", "$ceX", "$ceY", "$durationMs")
        )
        AutomationResult(code == 0, if (code == 0) "Swiped from ($csX, $csY) to ($ceX, $ceY)" else out)
    }

    override suspend fun pressBack(): AutomationResult = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext AutomationResult(false, "Shizuku not authorized")
        val (code, out) = shizukuManager.executeRawCommand(arrayOf("input", "keyevent", "4")) // KEYCODE_BACK
        AutomationResult(code == 0, if (code == 0) "Pressed Back." else out)
    }

    override suspend fun pressHome(): AutomationResult = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext AutomationResult(false, "Shizuku not authorized")
        val (code, out) = shizukuManager.executeRawCommand(arrayOf("input", "keyevent", "3")) // KEYCODE_HOME
        AutomationResult(code == 0, if (code == 0) "Pressed Home." else out)
    }

    override suspend fun takeScreenshot(): ScreenshotResult = withContext(Dispatchers.IO) {
        // Shizuku screencap
        val path = "/data/local/tmp/eva_screencap.png"
        val (code, _) = shizukuManager.executeRawCommand(arrayOf("screencap", "-p", path))
        if (code == 0) {
            ScreenshotResult(true, "Screenshot captured via Shizuku shell at $path")
        } else {
            ScreenshotResult(false, "Failed to capture screenshot via Shizuku")
        }
    }

    private suspend fun triggerDebugCrosshair(x: Int, y: Int) {
        val settings = preferences.settingsFlow.first()
        if (settings.debugTapCrosshair) {
            EvaOverlayService.showTapCrosshair(x, y)
        }
    }
}
