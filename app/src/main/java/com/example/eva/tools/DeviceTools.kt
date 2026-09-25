package com.example.eva.tools

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import java.io.File

class DeviceTools(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var isFlashlightOn = false

    fun toggleFlashlight(enable: Boolean? = null): ToolExecutionResult {
        return try {
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }

            if (cameraId == null) {
                return ToolExecutionResult(false, "Flashlight is not available on this device.")
            }

            val targetState = enable ?: !isFlashlightOn
            cameraManager.setTorchMode(cameraId, targetState)
            isFlashlightOn = targetState

            val stateText = if (targetState) "on" else "off"
            ToolExecutionResult(true, "Flashlight turned $stateText.", data = targetState)
        } catch (e: Exception) {
            ToolExecutionResult(false, "Failed to toggle flashlight: ${e.localizedMessage}")
        }
    }

    fun isTorchActive(): Boolean = isFlashlightOn

    fun setVolume(percentage: Int): ToolExecutionResult {
        return try {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val targetVolume = (percentage.coerceIn(0, 100) * maxVolume) / 100
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, AudioManager.FLAG_SHOW_UI)
            ToolExecutionResult(true, "Media volume set to $percentage percent.")
        } catch (e: Exception) {
            ToolExecutionResult(false, "Failed to set volume: ${e.localizedMessage}")
        }
    }

    fun adjustVolume(increase: Boolean): ToolExecutionResult {
        return try {
            val direction = if (increase) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val percent = (current * 100) / max
            ToolExecutionResult(true, "Volume ${if (increase) "increased" else "decreased"} to $percent percent.")
        } catch (e: Exception) {
            ToolExecutionResult(false, "Could not adjust volume: ${e.localizedMessage}")
        }
    }

    fun muteVolume(mute: Boolean): ToolExecutionResult {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val direction = if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            } else {
                audioManager.setStreamMute(AudioManager.STREAM_MUSIC, mute)
            }
            ToolExecutionResult(true, if (mute) "Media muted." else "Media unmuted.")
        } catch (e: Exception) {
            ToolExecutionResult(false, "Failed to adjust mute: ${e.localizedMessage}")
        }
    }

    fun getDeviceStatus(): ToolExecutionResult {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val batteryLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val isCharging = bm.isCharging

            val stat = StatFs(Environment.getDataDirectory().path)
            val totalBytes = stat.blockCountLong * stat.blockSizeLong
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            val totalGb = totalBytes / (1024 * 1024 * 1024)
            val availableGb = availableBytes / (1024 * 1024 * 1024)

            val runtime = Runtime.getRuntime()
            val usedMemMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
            val maxMemMb = runtime.maxMemory() / (1024 * 1024)

            val statusMsg = "Battery is at $batteryLevel%${if (isCharging) " (charging)" else ""}. " +
                    "Storage: ${availableGb}GB free of ${totalGb}GB. " +
                    "Device: ${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL} running Android ${Build.VERSION.RELEASE}."

            ToolExecutionResult(
                isSuccess = true,
                message = statusMsg,
                data = mapOf(
                    "batteryLevel" to batteryLevel,
                    "isCharging" to isCharging,
                    "availableStorageGb" to availableGb,
                    "totalStorageGb" to totalGb,
                    "usedMemoryMb" to usedMemMb,
                    "model" to Build.MODEL,
                    "androidVersion" to Build.VERSION.RELEASE
                )
            )
        } catch (e: Exception) {
            ToolExecutionResult(false, "Failed to read device status: ${e.localizedMessage}")
        }
    }

    fun openSettings(settingType: String): ToolExecutionResult {
        return try {
            val action = when (settingType.lowercase()) {
                "wifi", "wi-fi" -> Settings.ACTION_WIFI_SETTINGS
                "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                "display" -> Settings.ACTION_DISPLAY_SETTINGS
                "sound", "audio", "volume" -> Settings.ACTION_SOUND_SETTINGS
                "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
                "apps", "applications" -> Settings.ACTION_APPLICATION_SETTINGS
                "developer", "wireless_debugging" -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
                else -> Settings.ACTION_SETTINGS
            }
            val intent = Intent(action).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            context.startActivity(intent)
            ToolExecutionResult(true, "Opening $settingType settings.")
        } catch (e: Exception) {
            ToolExecutionResult(false, "Could not open $settingType settings: ${e.localizedMessage}")
        }
    }
}
