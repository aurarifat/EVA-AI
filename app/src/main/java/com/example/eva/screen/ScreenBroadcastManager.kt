package com.example.eva.screen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * Manages real-time, user-authorized Live Screen Broadcasting via Android MediaProjection.
 * Throttles frame captures to preserve battery and memory.
 * Emits live thumbnails to Compose and base64 frames to vision-capable AI providers.
 */
class ScreenBroadcastManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "ScreenBroadcastManager"

        // Capture dimensions: 540x960 (efficient, lightweight, perfect for AI vision analysis)
        private const val VIRTUAL_WIDTH = 540
        private const val VIRTUAL_HEIGHT = 960
        private const val VIRTUAL_DENSITY = 240

        @Volatile
        private var instance: ScreenBroadcastManager? = null

        fun getInstance(context: Context): ScreenBroadcastManager {
            return instance ?: synchronized(this) {
                instance ?: ScreenBroadcastManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private val mediaProjectionManager =
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // Broadcast States
    private val _isBroadcasting = MutableStateFlow(false)
    val isBroadcasting: StateFlow<Boolean> = _isBroadcasting.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _latestThumbnail = MutableStateFlow<Bitmap?>(null)
    val latestThumbnail: StateFlow<Bitmap?> = _latestThumbnail.asStateFlow()

    private val _statusMessage = MutableStateFlow("Ready to broadcast screen")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    // Listener callbacks for Service notification integration
    var onBroadcastStateChanged: ((Boolean) -> Unit)? = null

    private var lastCapturedBitmap: Bitmap? = null
    private var lastCaptureTime = 0L

    fun getScreenCaptureIntent(): Intent {
        return mediaProjectionManager.createScreenCaptureIntent()
    }

    /**
     * Starts the live screen broadcast using user-granted MediaProjection token.
     */
    fun startBroadcast(resultCode: Int, data: Intent): Boolean {
        if (resultCode != Activity.RESULT_OK) {
            _statusMessage.value = "Screen capture permission denied by user."
            return false
        }

        stopBroadcast()

        try {
            // Ensure running overlay foreground service has MEDIA_PROJECTION type promoted
            com.example.eva.overlay.EvaOverlayService.activeInstance?.startForegroundNotification(isBroadcastingOverride = true)

            val projection = mediaProjectionManager.getMediaProjection(resultCode, data)
            if (projection == null) {
                _statusMessage.value = "Failed to obtain MediaProjection handle."
                Log.e(TAG, "mediaProjectionManager.getMediaProjection returned null")
                return false
            }
            mediaProjection = projection

            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection session stopped by system or user.")
                    stopBroadcast()
                }
            }, mainHandler)

            // Dynamic resolution matching current device aspect ratio for flawless scaling
            val metrics = context.resources.displayMetrics
            val rawW = if (metrics.widthPixels > 0) metrics.widthPixels else 1080
            val rawH = if (metrics.heightPixels > 0) metrics.heightPixels else 1920
            val density = if (metrics.densityDpi > 0) metrics.densityDpi else 240

            // Ensure dimensions are positive even numbers scaled for AI vision analysis (approx 540p width)
            val scale = (rawW.toFloat() / 540f).coerceAtLeast(1.0f)
            val targetW = (((rawW / scale).toInt() / 2) * 2).coerceIn(360, 1080)
            val targetH = (((rawH / scale).toInt() / 2) * 2).coerceIn(640, 2400)

            // Setup ImageReader with 3 buffers to prevent frame dropping
            val reader = ImageReader.newInstance(
                targetW,
                targetH,
                PixelFormat.RGBA_8888,
                3
            )
            imageReader = reader

            reader.setOnImageAvailableListener({ ir ->
                handleNewFrame(ir)
            }, mainHandler)

            // Create VirtualDisplay with AUTO_MIRROR, falling back to public if mirror-only flag unsupported
            virtualDisplay = try {
                projection.createVirtualDisplay(
                    "EvaLiveScreenBroadcast",
                    targetW,
                    targetH,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    mainHandler
                )
            } catch (ex: Exception) {
                Log.w(TAG, "VirtualDisplay AUTO_MIRROR flag rejected, attempting fallback: ${ex.message}")
                projection.createVirtualDisplay(
                    "EvaLiveScreenBroadcast",
                    targetW,
                    targetH,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                    reader.surface,
                    null,
                    mainHandler
                )
            }

            _isBroadcasting.value = true
            _isPaused.value = false
            _statusMessage.value = "Live Screen Broadcast Active"
            onBroadcastStateChanged?.invoke(true)
            Log.i(TAG, "Screen broadcast successfully started at ${targetW}x${targetH} @ ${density}dpi")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting screen broadcast: ${e.message}", e)
            _statusMessage.value = "Broadcast error: ${e.localizedMessage ?: "Unknown error"}"
            stopBroadcast()
            return false
        }
    }

    private fun handleNewFrame(reader: ImageReader) {
        if (_isPaused.value) {
            // Drain images while paused to prevent buffer overflow
            val image = reader.acquireLatestImage()
            image?.close()
            return
        }

        val now = System.currentTimeMillis()
        // Throttle UI preview thumbnail updates to ~1-2 fps to minimize CPU/battery overhead
        if (now - lastCaptureTime < 700L) {
            val image = reader.acquireLatestImage()
            image?.close()
            return
        }
        lastCaptureTime = now

        val image: Image = reader.acquireLatestImage() ?: return
        scope.launch(Dispatchers.Default) {
            try {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * VIRTUAL_WIDTH

                val bitmap = Bitmap.createBitmap(
                    VIRTUAL_WIDTH + rowPadding / pixelStride,
                    VIRTUAL_HEIGHT,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)

                // Crop row padding if present
                val cleanBitmap = if (rowPadding > 0) {
                    Bitmap.createBitmap(bitmap, 0, 0, VIRTUAL_WIDTH, VIRTUAL_HEIGHT)
                } else {
                    bitmap
                }

                synchronized(this@ScreenBroadcastManager) {
                    lastCapturedBitmap = cleanBitmap
                }

                // Downscale small preview for dock UI
                val thumbnail = Bitmap.createScaledBitmap(cleanBitmap, 180, 320, true)
                _latestThumbnail.value = thumbnail
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing screen frame: ${e.message}")
            } finally {
                image.close()
            }
        }
    }

    fun pauseBroadcast() {
        if (_isBroadcasting.value && !_isPaused.value) {
            _isPaused.value = true
            _statusMessage.value = "Screen Broadcast Paused"
            Log.i(TAG, "Screen broadcast paused.")
        }
    }

    fun resumeBroadcast() {
        if (_isBroadcasting.value && _isPaused.value) {
            _isPaused.value = false
            _statusMessage.value = "Live Screen Broadcast Active"
            Log.i(TAG, "Screen broadcast resumed.")
        }
    }

    fun stopBroadcast() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null

            imageReader?.close()
            imageReader = null

            mediaProjection?.stop()
            mediaProjection = null

            _isBroadcasting.value = false
            _isPaused.value = false
            _latestThumbnail.value = null
            _statusMessage.value = "Screen broadcast stopped."
            onBroadcastStateChanged?.invoke(false)
            Log.i(TAG, "Screen broadcast stopped and resources released.")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping broadcast: ${e.message}", e)
        }
    }

    /**
     * Captures the latest real-time frame as a JPEG Base64 string for vision AI analysis.
     */
    fun captureCurrentFrameBase64(): String? {
        val bmp = synchronized(this) { lastCapturedBitmap } ?: return null
        return try {
            val outputStream = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
            Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Error compressing screen frame: ${e.message}")
            null
        }
    }

    /**
     * Captures the latest bitmap for local OCR or accessibility inspection.
     */
    fun captureCurrentFrameBitmap(): Bitmap? {
        return synchronized(this) { lastCapturedBitmap }
    }
}
