package com.example.eva.shizuku

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

data class UiScreenNode(
    val text: String = "",
    val contentDescription: String = "",
    val resourceId: String = "",
    val className: String = "",
    val packageName: String = "",
    val bounds: Rect = Rect(),
    val clickable: Boolean = false,
    val checked: Boolean = false,
    val enabled: Boolean = true
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

class DeviceScreenAutomation(
    private val context: Context,
    private val shizukuManager: ShizukuManager
) {

    private val geometryManager = com.example.eva.automation.ScreenGeometryManager(context)
    private val boundsPattern = Pattern.compile("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]")

    /**
     * Dumps the foreground Android screen UI hierarchy using Shizuku's privileged shell.
     */
    suspend fun dumpScreenHierarchy(): List<UiScreenNode> = withContext(Dispatchers.IO) {
        if (!shizukuManager.shizukuState.value.isAuthorized) {
            return@withContext emptyList()
        }

        try {
            // Execute uiautomator dump to a temporary file, then read it
            val dumpPath = "/data/local/tmp/eva_dump.xml"
            shizukuManager.executeRawCommand(arrayOf("uiautomator", "dump", dumpPath))
            val (code, xml) = shizukuManager.executeRawCommand(arrayOf("cat", dumpPath))

            if (code != 0 || xml.isBlank()) {
                return@withContext emptyList()
            }

            parseUiHierarchyXml(xml)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseUiHierarchyXml(xml: String): List<UiScreenNode> {
        val nodes = mutableListOf<UiScreenNode>()
        val nodeMatcher = Pattern.compile("<node\\b([^>]+)/?>").matcher(xml)

        while (nodeMatcher.find()) {
            val attributes = nodeMatcher.group(1) ?: continue
            val text = extractAttribute(attributes, "text")
            val contentDesc = extractAttribute(attributes, "content-desc")
            val resourceId = extractAttribute(attributes, "resource-id")
            val className = extractAttribute(attributes, "class")
            val packageName = extractAttribute(attributes, "package")
            val clickable = extractAttribute(attributes, "clickable").toBoolean()
            val checked = extractAttribute(attributes, "checked").toBoolean()
            val enabled = extractAttribute(attributes, "enabled").toBoolean()
            val boundsStr = extractAttribute(attributes, "bounds")

            val rect = Rect()
            val bMatcher = boundsPattern.matcher(boundsStr)
            if (bMatcher.find()) {
                val left = bMatcher.group(1)?.toIntOrNull() ?: 0
                val top = bMatcher.group(2)?.toIntOrNull() ?: 0
                val right = bMatcher.group(3)?.toIntOrNull() ?: 0
                val bottom = bMatcher.group(4)?.toIntOrNull() ?: 0
                rect.set(left, top, right, bottom)
            }

            nodes.add(
                UiScreenNode(
                    text = text,
                    contentDescription = contentDesc,
                    resourceId = resourceId,
                    className = className,
                    packageName = packageName,
                    bounds = rect,
                    clickable = clickable,
                    checked = checked,
                    enabled = enabled
                )
            )
        }

        return nodes
    }

    private fun extractAttribute(attributes: String, name: String): String {
        val p = Pattern.compile("$name=\"([^\"]*)\"")
        val m = p.matcher(attributes)
        return if (m.find()) m.group(1) ?: "" else ""
    }

    /**
     * Taps the specified screen coordinates using Shizuku privileged shell.
     */
    suspend fun tap(x: Int, y: Int): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (!shizukuManager.shizukuState.value.isAuthorized) {
            return@withContext Pair(false, "Shizuku authorization required to tap screen.")
        }
        val (code, out) = shizukuManager.executeRawCommand(arrayOf("input", "tap", "$x", "$y"))
        if (code == 0) {
            Pair(true, "Tapped at coordinates ($x, $y).")
        } else {
            Pair(false, "Failed to tap: $out")
        }
    }

    /**
     * Searches foreground screen for an element matching the query and clicks it.
     */
    suspend fun clickByText(query: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val nodes = dumpScreenHierarchy()
        if (nodes.isEmpty()) {
            return@withContext Pair(false, "Unable to read device screen. Please ensure Shizuku is authorized.")
        }

        // 1. Look for exact or substring match in text or content description
        val target = nodes.firstOrNull { it.matchesQuery(query) && !it.bounds.isEmpty }
            ?: nodes.firstOrNull { (it.text.contains(query, ignoreCase = true) || it.contentDescription.contains(query, ignoreCase = true)) && !it.bounds.isEmpty }

        if (target != null) {
            val (ok, _) = tap(target.centerX, target.centerY)
            if (ok) {
                val label = target.text.ifBlank { target.contentDescription.ifBlank { query } }
                return@withContext Pair(true, "Clicked '$label' at (${target.centerX}, ${target.centerY}).")
            }
        }

        Pair(false, "Could not find element matching '$query' on the current screen.")
    }

    /**
     * Analyzes the active screen to detect and close ads (popups, banners, video interstitials).
     */
    suspend fun closeAds(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val nodes = dumpScreenHierarchy()

        // 1. Check for explicit close buttons by text or description
        val adCloseKeywords = listOf(
            "close", "skip", "dismiss", "no thanks", "continue to app", "skip ad",
            "close ad", "✕", "x", "×", "not now", "later", "close button", "dismiss ad", "done"
        )

        val explicitNode = nodes.firstOrNull { node ->
            !node.bounds.isEmpty && (
                adCloseKeywords.any { kw -> node.text.equals(kw, ignoreCase = true) || node.contentDescription.equals(kw, ignoreCase = true) } ||
                node.resourceId.lowercase().contains("close") ||
                node.resourceId.lowercase().contains("dismiss") ||
                node.resourceId.lowercase().contains("skip") ||
                node.resourceId.lowercase().contains("btn_close")
            )
        }

        if (explicitNode != null) {
            val (ok, _) = tap(explicitNode.centerX, explicitNode.centerY)
            if (ok) {
                return@withContext Pair(true, "Detected ad close button and closed the ad.")
            }
        }

        // 2. Check for corner close icon (top-right or top-left corner clickable small element)
        val (screenWidth, screenHeight) = geometryManager.refreshDimensions()
        val cornerNode = nodes.firstOrNull { node ->
            node.clickable && node.bounds.width() in 20..260 && node.bounds.height() in 20..260 &&
                node.bounds.top < (screenHeight * 0.20) && (node.bounds.left > (screenWidth * 0.65) || node.bounds.right < (screenWidth * 0.35))
        }

        if (cornerNode != null) {
            val (ok, _) = tap(cornerNode.centerX, cornerNode.centerY)
            if (ok) {
                return@withContext Pair(true, "Closed ad via corner dismissal icon.")
            }
        }

        // 3. Fallback: Privileged Back keyevent (KEYCODE_BACK = 4) dismisses 95% of interstitials
        val (backOk, _) = shizukuManager.executeRawCommand(arrayOf("input", "keyevent", "4"))
        if (backOk == 0) {
            Pair(true, "Dismissed ad overlay via back signal.")
        } else {
            Pair(false, "Could not find an active ad to close.")
        }
    }

    /**
     * Analyzes screen and turns on protection (for AdGuard, VPNs, Antivirus, DNS apps).
     */
    suspend fun turnProtectionOn(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val nodes = dumpScreenHierarchy()

        // 1. Look for switch/toggle or button for "protection"
        val protectionKeywords = listOf(
            "protection", "turn on", "enable", "start protection", "protect",
            "connect", "dns protection", "ad blocker", "adguard", "switch"
        )

        // Find switch or button
        val switchNode = nodes.firstOrNull { node ->
            !node.bounds.isEmpty && (
                node.className.contains("Switch", ignoreCase = true) ||
                node.className.contains("ToggleButton", ignoreCase = true) ||
                node.className.contains("CompoundButton", ignoreCase = true) ||
                node.resourceId.contains("switch", ignoreCase = true) ||
                node.resourceId.contains("protection", ignoreCase = true)
            ) && !node.checked
        }

        if (switchNode != null) {
            val (ok, _) = tap(switchNode.centerX, switchNode.centerY)
            if (ok) {
                return@withContext Pair(true, "Toggled protection switch ON.")
            }
        }

        val textNode = nodes.firstOrNull { node ->
            !node.bounds.isEmpty && protectionKeywords.any { kw -> node.matchesQuery(kw) }
        }

        if (textNode != null) {
            val (ok, _) = tap(textNode.centerX, textNode.centerY)
            if (ok) {
                return@withContext Pair(true, "Tapped '${textNode.text.ifBlank { "protection" }}' to turn protection on.")
            }
        }

        // Dynamic center coordinates via geometryManager (upper-mid screen)
        val (w, h) = geometryManager.refreshDimensions()
        val (centerOk, _) = tap(w / 2, (h * 0.40).toInt())
        if (centerOk) {
            Pair(true, "Activated central protection switch.")
        } else {
            Pair(false, "Could not locate the protection toggle on the current screen.")
        }
    }

    /**
     * Executes scroll gestures dynamically based on runtime screen dimensions.
     */
    suspend fun scrollDown(distance: Int = 800): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val (w, h) = geometryManager.refreshDimensions()
        val startY = (h * 0.70).toInt()
        val endY = (startY - distance).coerceAtLeast((h * 0.15).toInt())
        val (code, out) = shizukuManager.executeRawCommand(
            arrayOf("input", "swipe", "${w / 2}", "$startY", "${w / 2}", "$endY", "300")
        )
        if (code == 0) Pair(true, "Scrolled down.") else Pair(false, out)
    }

    suspend fun scrollUp(distance: Int = 800): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val (w, h) = geometryManager.refreshDimensions()
        val startY = (h * 0.25).toInt()
        val endY = (startY + distance).coerceAtMost((h * 0.85).toInt())
        val (code, out) = shizukuManager.executeRawCommand(
            arrayOf("input", "swipe", "${w / 2}", "$startY", "${w / 2}", "$endY", "300")
        )
        if (code == 0) Pair(true, "Scrolled up.") else Pair(false, out)
    }

    suspend fun slideLeft(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val (w, h) = geometryManager.refreshDimensions()
        val cy = h / 2
        val (code, out) = shizukuManager.executeRawCommand(
            arrayOf("input", "swipe", "${(w * 0.85).toInt()}", "$cy", "${(w * 0.15).toInt()}", "$cy", "300")
        )
        if (code == 0) Pair(true, "Slid left.") else Pair(false, out)
    }

    suspend fun slideRight(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val (w, h) = geometryManager.refreshDimensions()
        val cy = h / 2
        val (code, out) = shizukuManager.executeRawCommand(
            arrayOf("input", "swipe", "${(w * 0.15).toInt()}", "$cy", "${(w * 0.85).toInt()}", "$cy", "300")
        )
        if (code == 0) Pair(true, "Slid right.") else Pair(false, out)
    }

    /**
     * Launches app by name with dedicated AdGuard handling.
     */
    suspend fun launchApp(appName: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val lower = appName.trim().lowercase()

        // 1. Check for AdGuard specifically
        if (lower.contains("adguard") || lower.contains("ad guard")) {
            val pm = context.packageManager
            val packages = listOf(
                "com.adguard.android",
                "com.adguard.android.contentblocker",
                "com.adguard.vpn"
            )

            for (pkg in packages) {
                val intent = pm.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return@withContext Pair(true, "Opening AdGuard.")
                }
            }

            // Privileged monkey/am start attempt via Shizuku
            if (shizukuManager.shizukuState.value.isAuthorized) {
                val (code, _) = shizukuManager.executeRawCommand(
                    arrayOf("monkey", "-p", "com.adguard.android", "-c", "android.intent.category.LAUNCHER", "1")
                )
                if (code == 0) {
                    return@withContext Pair(true, "Opening AdGuard via Shizuku shell.")
                }
            }
        }

        // 2. Generic package search
        val pm = context.packageManager
        val installed = pm.getInstalledApplications(0)
        val match = installed.firstOrNull { app ->
            val label = pm.getApplicationLabel(app).toString()
            label.equals(appName, ignoreCase = true) || label.lowercase().contains(lower) || app.packageName.lowercase().contains(lower)
        }

        if (match != null) {
            val intent = pm.getLaunchIntentForPackage(match.packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                val label = pm.getApplicationLabel(match).toString()
                return@withContext Pair(true, "Opening $label.")
            }
        }

        Pair(false, "Could not find an application matching '$appName'.")
    }
}
