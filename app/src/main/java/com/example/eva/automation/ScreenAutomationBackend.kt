package com.example.eva.automation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager

/**
 * Normalized UI Screen Node representing an interactive or visible element.
 * Note: Split-screen / multi-window mode is explicitly unsupported. Screen dimensions
 * assume full-window bounds.
 */
data class UiDumpNode(
    val text: String = "",
    val contentDescription: String = "",
    val resourceId: String = "",
    val className: String = "",
    val packageName: String = "",
    val bounds: Rect = Rect(),
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
    val isScrollable: Boolean = false,
    val isChecked: Boolean = false,
    val isEnabled: Boolean = true,
    val isPassword: Boolean = false,
    val depth: Int = 0
) {
    val centerX: Int get() = bounds.centerX()
    val centerY: Int get() = bounds.centerY()

    fun matchesQuery(query: String): Boolean {
        val q = query.trim().lowercase()
        return text.lowercase().contains(q) ||
                contentDescription.lowercase().contains(q) ||
                resourceId.substringAfter(":id/").lowercase().contains(q)
    }
}

enum class ScrollDirection {
    UP, DOWN, LEFT, RIGHT
}

data class AutomationResult(
    val isSuccess: Boolean,
    val message: String,
    val suggestedFallbackAction: String? = null, // e.g. "click_at" when clickByText finds no target
    val targetCoordinates: Pair<Int, Int>? = null
)

data class ScreenshotResult(
    val isSuccess: Boolean,
    val message: String,
    val bitmap: Bitmap? = null
)

/**
 * Screen dimensions & rotation state manager.
 * Dynamic runtime resolution without hardcoded 1080x2400 assumptions.
 */
class ScreenGeometryManager(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var cachedWidth: Int = 0
    private var cachedHeight: Int = 0
    private var lastRotation: Int = -1

    init {
        refreshDimensions()
    }

    fun invalidateCache() {
        cachedWidth = 0
        cachedHeight = 0
        lastRotation = -1
    }

    @Synchronized
    fun refreshDimensions(): Pair<Int, Int> {
        val currentRotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }

        if (cachedWidth > 0 && cachedHeight > 0 && currentRotation == lastRotation) {
            return Pair(cachedWidth, cachedHeight)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            cachedWidth = bounds.width().coerceAtLeast(1)
            cachedHeight = bounds.height().coerceAtLeast(1)
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            cachedWidth = dm.widthPixels.coerceAtLeast(1)
            cachedHeight = dm.heightPixels.coerceAtLeast(1)
        }

        lastRotation = currentRotation
        Log.d("ScreenGeometryManager", "Refreshed dimensions: ${cachedWidth}x${cachedHeight}, rotation: $currentRotation")
        return Pair(cachedWidth, cachedHeight)
    }

    /**
     * Clamps x and y coordinates to real screen boundaries [0, width] and [0, height].
     * Logs whenever a clamp was enforced to catch model hallucinations.
     */
    fun clampCoordinates(x: Int, y: Int): Pair<Int, Int> {
        val (w, h) = refreshDimensions()
        val clampedX = x.coerceIn(0, w)
        val clampedY = y.coerceIn(0, h)
        if (clampedX != x || clampedY != y) {
            Log.w("ScreenGeometryManager", "Coordinate clamp occurred! Requested: ($x, $y) -> Clamped: ($clampedX, $clampedY) against screen: ${w}x${h}")
        }
        return Pair(clampedX, clampedY)
    }

    fun getWidth(): Int = refreshDimensions().first
    fun getHeight(): Int = refreshDimensions().second
}

/**
 * Common abstraction for Android screen automation.
 * Supports AccessibilityBackend and ShizukuBackend.
 */
interface ScreenAutomationBackend {
    val backendName: String
    val geometryManager: ScreenGeometryManager

    fun isAvailable(): Boolean

    suspend fun dumpScreen(): List<UiDumpNode>

    suspend fun clickByText(text: String): AutomationResult

    suspend fun clickAt(x: Int, y: Int): AutomationResult

    suspend fun typeText(text: String, fieldHint: String? = null): AutomationResult

    suspend fun pressEnter(): AutomationResult

    suspend fun scroll(direction: ScrollDirection): AutomationResult

    suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long = 300L): AutomationResult

    suspend fun pressBack(): AutomationResult

    suspend fun pressHome(): AutomationResult

    suspend fun takeScreenshot(): ScreenshotResult
}

/**
 * Compresses and formats the screen node hierarchy into an ultra-concise token-efficient
 * representation for fast LLM agent step decisions (<100 tokens, <500 characters).
 */
fun List<UiDumpNode>.formatForFastAgent(geometryManager: ScreenGeometryManager): String {
    val (w, h) = geometryManager.refreshDimensions()
    val sb = StringBuilder()
    sb.appendLine("Screen: ${w}x${h}")
    sb.appendLine("Actionable elements:")

    val seen = mutableSetOf<String>()
    var count = 0
    for (node in this) {
        val label = node.text.ifBlank { node.contentDescription }.trim()
        val isActionable = node.isClickable || node.isEditable || node.isScrollable
        if (label.isBlank() && !isActionable) continue

        // Key to deduplicate exact repeated labels at similar coordinates
        val key = "$label@${node.centerX / 20}_${node.centerY / 20}"
        if (!seen.add(key)) continue

        val actions = mutableListOf<String>()
        if (node.isClickable) actions.add("click")
        if (node.isEditable) actions.add("edit")
        if (node.isScrollable) actions.add("scroll")
        val actStr = if (actions.isNotEmpty()) " [${actions.joinToString(",")}]" else ""

        count++
        val labelStr = if (label.isNotBlank()) "\"$label\"" else "<icon/container>"
        sb.appendLine("$count. $labelStr @ (${node.centerX}, ${node.centerY})$actStr")
        if (count >= 30) break
    }

    if (count == 0) {
        sb.appendLine("(No actionable text elements found; try click_at or scroll)")
    }
    return sb.toString()
}
