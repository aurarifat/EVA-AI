package com.example.eva.screen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Transparent trampoline activity to launch the official Android Screen Capture permission dialog
 * when initiated from the floating overlay, background services, or UI buttons.
 */
class ScreenCapturePermissionActivity : ComponentActivity() {

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val resultCode = result.resultCode
        val data = result.data

        if (resultCode == Activity.RESULT_OK && data != null) {
            android.util.Log.i("ScreenCaptureActivity", "Screen capture permission granted by user. Initializing broadcast...")

            // 1. Promote running overlay foreground service with MEDIA_PROJECTION type immediately
            // while this Activity is guaranteed in the foreground
            val overlay = com.example.eva.overlay.EvaOverlayService.activeInstance
            overlay?.startForegroundNotification(isBroadcastingOverride = true)

            // 2. Start the broadcast manager directly while the Activity window is guaranteed foreground
            val broadcastMgr = com.example.eva.screen.ScreenBroadcastManager.getInstance(applicationContext)
            val ok = broadcastMgr.startBroadcast(resultCode, data)

            if (ok) {
                Toast.makeText(this, "Live Screen Broadcast Started Successfully!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Could not start screen broadcast: ${broadcastMgr.statusMessage.value}", Toast.LENGTH_LONG).show()
            }

            // 3. Dispatch broadcast intent to EvaOverlayService to sync service state and notification
            val serviceIntent = Intent(this, com.example.eva.overlay.EvaOverlayService::class.java).apply {
                action = com.example.eva.overlay.EvaOverlayService.ACTION_START_BROADCAST
                putExtra(com.example.eva.overlay.EvaOverlayService.EXTRA_RESULT_CODE, resultCode)
                putExtra(com.example.eva.overlay.EvaOverlayService.EXTRA_RESULT_DATA, data)
            }
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    androidx.core.content.ContextCompat.startForegroundService(this, serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } catch (e: Exception) {
                android.util.Log.w("ScreenCaptureActivity", "Failed to start service intent: ${e.message}")
            }

            // Short delay before finishing to ensure projection surface and binder are bound
            window?.decorView?.postDelayed({
                finish()
            }, 300L) ?: finish()
        } else {
            android.util.Log.w("ScreenCaptureActivity", "Screen capture permission was not granted (resultCode: $resultCode)")
            Toast.makeText(this, "Screen capture permission was not granted", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = mgr.createScreenCaptureIntent()
        captureLauncher.launch(intent)
    }

    companion object {
        fun launch(context: Context) {
            val intent = Intent(context, ScreenCapturePermissionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }
}
