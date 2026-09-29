package com.example.eva.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * Native Android AccessibilityService implementation for EVA AI.
 * Delivers robust, low-latency screen analysis and precision gesture automation
 * without requiring root or external tools.
 */
class EvaAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "EvaAccessibilityService"
        var instance: EvaAccessibilityService? = null
            private set

        fun isServiceRunning(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "EVA Accessibility Service successfully connected.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Events monitored for UI state changes
    }

    override fun onInterrupt() {
        Log.w(TAG, "EVA Accessibility Service interrupted.")
    }

    override fun onDestroy() {
        if (instance == this) {
            instance = null
        }
        super.onDestroy()
        Log.i(TAG, "EVA Accessibility Service destroyed.")
    }

    /**
     * Traverses the active window node hierarchy and extracts structured UI elements.
     * Sensitive/password fields are detected and redacted. Zero-size and off-screen nodes are filtered.
     */
    fun dumpScreenHierarchy(deduplicate: Boolean = false): List<UiDumpNode> {
        val root = rootInActiveWindow ?: return emptyList()
        val nodes = mutableListOf<UiDumpNode>()
        try {
            traverseNode(root, 0, nodes)
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }

        return if (deduplicate) {
            // Deduplicate redundant empty container nodes that share identical bounds
            val seen = mutableSetOf<String>()
            nodes.filter { n ->
                val key = "${n.bounds.toShortString()}_${n.className}_${n.text}"
                seen.add(key)
            }
        } else {
            nodes
        }
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, depth: Int, result: MutableList<UiDumpNode>) {
        if (node == null) return

        val rect = Rect()
        node.getBoundsInScreen(rect)

        // Filter zero-size or non-positive bounds
        val isValidBounds = !rect.isEmpty && rect.width() > 0 && rect.height() > 0

        val isPassword = node.isPassword
        val text = if (isPassword) "[PASSWORD_REDACTED]" else (node.text?.toString() ?: "")
        val contentDesc = if (isPassword) "[PASSWORD_REDACTED]" else (node.contentDescription?.toString() ?: "")
        val className = node.className?.toString() ?: ""
        val packageName = node.packageName?.toString() ?: ""
        val resourceId = node.viewIdResourceName ?: ""

        val isClickable = node.isClickable
        val isEditable = node.isEditable
        val isScrollable = node.isScrollable
        val isChecked = node.isChecked
        val isEnabled = node.isEnabled

        if (isValidBounds && (text.isNotBlank() || contentDesc.isNotBlank() || isClickable || isEditable || isScrollable)) {
            result.add(
                UiDumpNode(
                    text = text,
                    contentDescription = contentDesc,
                    resourceId = resourceId,
                    className = className,
                    packageName = packageName,
                    bounds = rect,
                    isClickable = isClickable,
                    isEditable = isEditable,
                    isScrollable = isScrollable,
                    isChecked = isChecked,
                    isEnabled = isEnabled,
                    isPassword = isPassword,
                    depth = depth
                )
            )
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i)
            if (child != null) {
                traverseNode(child, depth + 1, result)
                @Suppress("DEPRECATION")
                child.recycle()
            }
        }
    }

    /**
     * Precision tap at (x, y) using GestureDescription and suspendCancellableCoroutine.
     * Snappy 65ms stroke for rapid agent execution.
     */
    suspend fun clickAt(x: Int, y: Int, durationMs: Long = 65L): AutomationResult = suspendCancellableCoroutine { cont ->
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(40L, 200L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) {
                    cont.resume(AutomationResult(true, "Tapped successfully at ($x, $y)", targetCoordinates = Pair(x, y)))
                }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) {
                    cont.resume(AutomationResult(false, "Tap gesture cancelled at ($x, $y)", suggestedFallbackAction = "click_at", targetCoordinates = Pair(x, y)))
                }
            }
        }, null)

        if (!dispatched) {
            if (cont.isActive) {
                cont.resume(AutomationResult(false, "System rejected gesture dispatch at ($x, $y)", suggestedFallbackAction = "click_at", targetCoordinates = Pair(x, y)))
            }
        }
    }

    /**
     * Precision swipe from (startX, startY) to (endX, endY) with duration.
     * Rapid 180ms stroke.
     */
    suspend fun swipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long = 180L
    ): AutomationResult = suspendCancellableCoroutine { cont ->
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(80L, 400L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) {
                    cont.resume(AutomationResult(true, "Swiped from ($startX, $startY) to ($endX, $endY)"))
                }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) {
                    cont.resume(AutomationResult(false, "Swipe gesture was cancelled by system"))
                }
            }
        }, null)

        if (!dispatched) {
            if (cont.isActive) {
                cont.resume(AutomationResult(false, "Failed to dispatch swipe gesture"))
            }
        }
    }

    /**
     * Finds matching node by text or content description and performs ACTION_CLICK.
     * Walks up to nearest clickable ancestor if the exact matching text container is not directly clickable.
     */
    suspend fun clickByText(query: String): AutomationResult = withContext(Dispatchers.Main) {
        val root = rootInActiveWindow ?: return@withContext AutomationResult(
            isSuccess = false,
            message = "Unable to read active window. Ensure screen is unlocked.",
            suggestedFallbackAction = "click_at"
        )

        val targetNode = findClickableNodeByText(root, query.trim())
        if (targetNode != null) {
            val rect = Rect()
            targetNode.getBoundsInScreen(rect)
            val clicked = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val label = targetNode.text?.toString() ?: targetNode.contentDescription?.toString() ?: query
            @Suppress("DEPRECATION")
            targetNode.recycle()
            @Suppress("DEPRECATION")
            root.recycle()

            if (clicked) {
                AutomationResult(true, "Clicked '$label' at (${rect.centerX()}, ${rect.centerY()})", targetCoordinates = Pair(rect.centerX(), rect.centerY()))
            } else {
                // If direct ACTION_CLICK failed, fallback to coordinate tap at center
                clickAt(rect.centerX(), rect.centerY())
            }
        } else {
            @Suppress("DEPRECATION")
            root.recycle()
            AutomationResult(
                isSuccess = false,
                message = "Could not find clickable element matching '$query' on the current screen.",
                suggestedFallbackAction = "click_at"
            )
        }
    }

    private fun findClickableNodeByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val lower = text.lowercase()
        // 1. System text search
        val matches = root.findAccessibilityNodeInfosByText(text)
        for (node in matches) {
            val clickableAncestor = findNearestClickableAncestor(node)
            if (clickableAncestor != null) {
                return clickableAncestor
            }
        }

        // 2. Recursive breadth/depth scan for substring and contentDescription
        return scanForMatchingNode(root, lower)
    }

    private fun scanForMatchingNode(node: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString()?.lowercase() ?: ""
        val nodeDesc = node.contentDescription?.toString()?.lowercase() ?: ""
        val resId = node.viewIdResourceName?.lowercase() ?: ""

        if (nodeText.contains(query) || nodeDesc.contains(query) || resId.contains(query)) {
            val clickable = findNearestClickableAncestor(node)
            if (clickable != null) return clickable
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            val found = scanForMatchingNode(child, query)
            if (found != null) {
                return found
            }
            @Suppress("DEPRECATION")
            child.recycle()
        }
        return null
    }

    private fun findNearestClickableAncestor(start: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = start
        while (current != null) {
            if (current.isClickable) {
                return current
            }
            current = current.parent
        }
        return start // Return original if no clickable parent, can attempt coordinate click
    }

    /**
     * Types text into the focused editable element, or attempts clipboard paste if ACTION_SET_TEXT is rejected.
     */
    suspend fun typeText(text: String, fieldHint: String? = null): AutomationResult = withContext(Dispatchers.Main) {
        val root = rootInActiveWindow ?: return@withContext AutomationResult(false, "Active window not accessible")

        var targetNode: AccessibilityNodeInfo? = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        if (targetNode == null && !fieldHint.isNullOrBlank()) {
            val found = root.findAccessibilityNodeInfosByText(fieldHint)
            targetNode = found.firstOrNull { it.isEditable } ?: found.firstOrNull()
        }

        if (targetNode == null) {
            targetNode = findAnyEditableNode(root)
        }

        if (targetNode == null) {
            @Suppress("DEPRECATION")
            root.recycle()
            return@withContext AutomationResult(false, "No active editable input field found on screen")
        }

        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val setSuccess = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

        if (!setSuccess) {
            // Clipboard paste fallback
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("EVA_INPUT", text))
            targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }

        @Suppress("DEPRECATION")
        targetNode.recycle()
        @Suppress("DEPRECATION")
        root.recycle()

        AutomationResult(true, "Entered text into input field.")
    }

    private fun findAnyEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findAnyEditableNode(child)
            if (found != null) return found
            @Suppress("DEPRECATION")
            child.recycle()
        }
        return null
    }

    fun pressBack(): AutomationResult {
        val success = performGlobalAction(GLOBAL_ACTION_BACK)
        return AutomationResult(success, if (success) "Pressed Back." else "Failed to trigger Back action.")
    }

    fun pressHome(): AutomationResult {
        val success = performGlobalAction(GLOBAL_ACTION_HOME)
        return AutomationResult(success, if (success) "Navigated Home." else "Failed to trigger Home action.")
    }

    suspend fun takeScreenshot(): com.example.eva.automation.ScreenshotResult = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val executor = Executor { it.run() }
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                        if (cont.isActive) {
                            val bitmap = try {
                                Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                            } catch (_: Exception) {
                                null
                            }
                            cont.resume(com.example.eva.automation.ScreenshotResult(true, "Screenshot captured successfully", bitmap))
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) {
                            cont.resume(com.example.eva.automation.ScreenshotResult(false, "Screenshot failed with error code $errorCode"))
                        }
                    }
                }
            )
        } else {
            cont.resume(com.example.eva.automation.ScreenshotResult(false, "Screenshot requires Android 11 (API 30) or above"))
        }
    }
}
