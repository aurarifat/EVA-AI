package com.example.eva.tools

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledAppInfo(
    val appName: String,
    val packageName: String,
    val icon: Drawable? = null,
    val isSystemApp: Boolean = false
)

class AppLauncherTools(private val context: Context) {

    private val packageManager: PackageManager = context.packageManager

    suspend fun getInstalledLaunchableApps(): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = packageManager.queryIntentActivities(intent, 0)
        resolveInfos.mapNotNull { resolveInfo ->
            val appInfo = resolveInfo.activityInfo.applicationInfo
            val name = resolveInfo.loadLabel(packageManager).toString()
            val pkg = resolveInfo.activityInfo.packageName
            val icon = resolveInfo.loadIcon(packageManager)
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            InstalledAppInfo(appName = name, packageName = pkg, icon = icon, isSystemApp = isSystem)
        }.sortedBy { it.appName.lowercase() }
    }

    suspend fun findApp(query: String): InstalledAppInfo? = withContext(Dispatchers.IO) {
        val apps = getInstalledLaunchableApps()
        val cleanedQuery = query.trim().lowercase()

        // Exact match
        apps.firstOrNull { it.appName.equals(cleanedQuery, ignoreCase = true) }
            ?: apps.firstOrNull { it.appName.lowercase().contains(cleanedQuery) }
            ?: apps.firstOrNull { it.packageName.lowercase().contains(cleanedQuery) }
    }

    fun launchAppByPackage(packageName: String): ToolExecutionResult {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                val label = try {
                    val info = packageManager.getApplicationInfo(packageName, 0)
                    packageManager.getApplicationLabel(info).toString()
                } catch (_: Exception) {
                    packageName
                }
                ToolExecutionResult(true, "Opening $label for you.", data = packageName)
            } else {
                ToolExecutionResult(false, "That app isn't installed on this device.")
            }
        } catch (e: Exception) {
            ToolExecutionResult(false, "Could not open app: ${e.localizedMessage}")
        }
    }

    suspend fun launchAppByName(name: String): ToolExecutionResult {
        val found = findApp(name)
        return if (found != null) {
            launchAppByPackage(found.packageName)
        } else {
            // Handle common well known apps with intent fallbacks
            when (name.trim().lowercase()) {
                "camera" -> {
                    val camIntent = Intent("android.media.action.IMAGE_CAPTURE").apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    if (camIntent.resolveActivity(packageManager) != null) {
                        context.startActivity(camIntent)
                        ToolExecutionResult(true, "Opening Camera.")
                    } else {
                        ToolExecutionResult(false, "That app isn't installed on this device.")
                    }
                }
                "settings" -> {
                    val settingsIntent = Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(settingsIntent)
                    ToolExecutionResult(true, "Opening Settings.")
                }
                "browser", "chrome" -> {
                    val browserIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://google.com")).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    if (browserIntent.resolveActivity(packageManager) != null) {
                        context.startActivity(browserIntent)
                        ToolExecutionResult(true, "Opening browser.")
                    } else {
                        ToolExecutionResult(false, "That app isn't installed on this device.")
                    }
                }
                else -> ToolExecutionResult(false, "That app isn't installed on this device.")
            }
        }
    }
}
