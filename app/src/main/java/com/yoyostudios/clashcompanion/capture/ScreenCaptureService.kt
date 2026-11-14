package com.yoyostudios.clashcompanion.capture

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
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicReference

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ClashCompanion"
        private const val CHANNEL_ID = "clash_companion_capture"
        private const val NOTIFICATION_ID = 1001
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"

        var instance: ScreenCaptureService? = null
            private set

        fun getLatestFrame(): Bitmap? = instance?.latestFrame?.get()

        /** Wall-clock timestamp for the frame currently exposed by getLatestFrame(). */
        fun latestFrameTimestampMs(): Long = instance?.lastFrameAtMs ?: 0L

        fun hasFreshFrame(maxAgeMs: Long = 1500L): Boolean {
            val service = instance ?: return false
            val timestamp = service.lastFrameAtMs
            if (timestamp <= 0L) return false
            val age = System.currentTimeMillis() - timestamp
            return age in 0..maxAgeMs
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val latestFrame = AtomicReference<Bitmap?>(null)
    @Volatile private var lastFrameAtMs: Long = 0L

    // Keep the previous frame alive for one extra capture cycle.
    // This prevents the bitmap recycling race where the HandDetector scanner
    // reads a frame via getLatestFrame() and it gets recycled before cropping finishes.
    // Cost: ~10MB extra memory. Benefit: zero recycled-bitmap crashes in scanner.
    private var previousFrame: Bitmap? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        Log.i(TAG, "ScreenCaptureService created: ${screenWidth}x${screenHeight} @ ${screenDensity}dpi")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }

        Log.i(TAG, "ScreenCapture: resultCode=$resultCode, data=${data != null}")

        if (data == null) {
            Log.e(TAG, "ScreenCapture: no data intent received")
            stopSelf()
            return START_NOT_STICKY
        }

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        if (mediaProjection == null) {
            Log.e(TAG, "ScreenCapture: MediaProjection is null")
            stopSelf()
            return START_NOT_STICKY
        }

        // Android 14+ requires registering a callback before createVirtualDisplay
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped by system")
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        startCapture()
        return START_NOT_STICKY
    }

    private fun startCapture() {
        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight,
            PixelFormat.RGBA_8888, 2
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val rowPadding = rowStride - pixelStride * screenWidth

                val bitmapWidth = screenWidth + rowPadding / pixelStride
                val bitmap = Bitmap.createBitmap(bitmapWidth, screenHeight, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)

                // Crop to exact screen size if row padding added extra pixels
                val cropped = if (bitmapWidth != screenWidth) {
                    Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight).also {
                        bitmap.recycle()
                    }
                } else {
                    bitmap
                }

                // Delayed recycling: recycle frame from TWO captures ago, not the previous one.
                // The scanner may still be reading the previous frame on another thread.
                val oldPrevious = previousFrame
                previousFrame = latestFrame.getAndSet(cropped)
                lastFrameAtMs = System.currentTimeMillis()
                oldPrevious?.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "ScreenCapture frame error: ${e.message}")
            } finally {
                image.close()
            }
        }, null)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ClashCompanionCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        Log.i(TAG, "ScreenCapture started: ${screenWidth}x${screenHeight}")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen Capture",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Clash Companion screen capture for card detection"
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Clash Companion")
            .setContentText("Screen capture active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        previousFrame?.recycle()
        previousFrame = null
        latestFrame.get()?.recycle()
        latestFrame.set(null)
        lastFrameAtMs = 0L
        instance = null
        Log.i(TAG, "ScreenCaptureService destroyed")
    }
}
