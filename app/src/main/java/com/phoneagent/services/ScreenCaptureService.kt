package com.phoneagent.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.phoneagent.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screen_capture_channel"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        var instance: ScreenCaptureService? = null
            private set
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode != -1 && resultData != null) {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
            setupVirtualDisplay()
        }

        return START_STICKY
    }

    private fun setupVirtualDisplay() {
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "PhoneAgentCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
        Log.d(TAG, "Virtual display created: ${screenWidth}x${screenHeight}")
    }

    suspend fun captureScreenshot(): String? = withContext(Dispatchers.IO) {
        try {
            val reader = imageReader ?: run {
                Log.w(TAG, "ImageReader not initialized")
                return@withContext null
            }

            // Wait briefly for a frame
            Thread.sleep(200)

            var image: Image? = null
            var attempts = 0
            while (image == null && attempts < 5) {
                image = reader.acquireLatestImage()
                if (image == null) {
                    Thread.sleep(100)
                    attempts++
                }
            }

            if (image == null) {
                Log.w(TAG, "Could not acquire image after $attempts attempts")
                return@withContext null
            }

            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            // Crop to actual screen size if needed
            val croppedBitmap = if (bitmap.width > screenWidth) {
                Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
            } else bitmap

            // Scale down for API efficiency (max 1080 wide)
            val scaledBitmap = if (croppedBitmap.width > 1080) {
                val scale = 1080f / croppedBitmap.width
                val newHeight = (croppedBitmap.height * scale).toInt()
                Bitmap.createScaledBitmap(croppedBitmap, 1080, newHeight, true)
            } else croppedBitmap

            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.PNG, 85, outputStream)
            val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

            if (scaledBitmap != croppedBitmap) scaledBitmap.recycle()
            if (croppedBitmap != bitmap) croppedBitmap.recycle()
            bitmap.recycle()

            base64
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot failed", e)
            null
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen Capture",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Required for PhoneAgent screen capture"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PhoneAgent Active")
            .setContentText("Agent is running and can see your screen")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        instance = null
        Log.d(TAG, "ScreenCaptureService destroyed")
    }
}
