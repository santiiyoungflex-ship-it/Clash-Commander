package com.ambix.assistant.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import com.ambix.assistant.strategy.AmbiXTacticalEngine
import com.ambix.assistant.strategy.PlannedMove
import com.ambix.assistant.vision.BattleSnapshot
import com.ambix.assistant.vision.Detection
import com.ambix.assistant.vision.HandCard
import com.ambix.assistant.vision.ObjectKind
import com.ambix.assistant.vision.TemporalDetectionFilter
import com.ambix.assistant.vision.TFLiteYoloDetector
import java.util.concurrent.atomic.AtomicReference

data class FrameReport(
    val width: Int,
    val height: Int,
    val sampledPixels: Int,
    val bluePixels: Int,
    val purplePixels: Int,
    val capturedAt: Long,
    val detections: Int = 0
)

object VisionState {
    val latest = AtomicReference<FrameReport?>(null)
    val latestDetections = AtomicReference<List<Detection>>(emptyList())
    val latestHand = AtomicReference<List<HandCard>>(emptyList())
    val latestElixir = AtomicReference<Int?>(null)
    val latestMove = AtomicReference<PlannedMove?>(null)
    val status = AtomicReference("Captura detenida")
    val modelStatus = AtomicReference("Modelos TFLite no inicializados")
    val modelReady = AtomicReference(false)
}

class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var arenaDetector: TFLiteYoloDetector? = null
    private var hudDetector: TFLiteYoloDetector? = null
    private var processingThread: HandlerThread? = null
    private var processingHandler: Handler? = null
    private var lastProcessedAtMs = 0L
    private val arenaFilter = TemporalDetectionFilter(windowSize = 5, minHits = 3)
    private val hudFilter = TemporalDetectionFilter(
        windowSize = 5,
        minHits = 3,
        confidenceFloors = mapOf(
            ObjectKind.TROOP to 0.65f,
            ObjectKind.BUILDING to 0.65f,
            ObjectKind.TOWER to 0.65f,
            ObjectKind.SPELL to 0.65f,
            ObjectKind.CARD_IN_HAND to 0.62f,
            ObjectKind.ELIXIR to 0.68f,
            ObjectKind.UNKNOWN to 0.80f
        )
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopCapture()
            stopSelf()
            return START_NOT_STICKY
        }
        val result = intent?.getIntExtra(EXTRA_RESULT, 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") (intent?.getParcelableExtra(EXTRA_DATA) as? Intent)
        if (data == null) {
            VisionState.status.set("Falta autorización de captura")
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("AmbiX")
            .setContentText("Captura y visión local activas")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(41, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(41, notification)

        try {
            arenaDetector?.close()
            hudDetector?.close()
            arenaDetector = TFLiteYoloDetector(this)
            hudDetector = TFLiteYoloDetector(this, HUD_MODEL_ASSET, HUD_LABELS_ASSET)
            val arenaReady = arenaDetector?.isModelLoaded == true
            val hudReady = hudDetector?.isModelLoaded == true
            VisionState.modelReady.set(arenaReady && hudReady)
            VisionState.modelStatus.set(
                "Arena: ${arenaDetector?.diagnostic} · HUD: ${hudDetector?.diagnostic}"
            )

            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(result, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopCapture()
                    stopSelf()
                }
            }, null)
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            processingThread = HandlerThread("AmbiX-Vision").also { it.start() }
            processingHandler = Handler(processingThread!!.looper)
            reader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
            reader?.setOnImageAvailableListener({ imageReader ->
                var image: Image? = null
                try {
                    image = imageReader.acquireLatestImage()
                    if (image != null) analyze(image)
                } catch (error: Throwable) {
                    VisionState.status.set("Error procesando fotograma: ${error.javaClass.simpleName}")
                } finally {
                    image?.close()
                }
            }, processingHandler)
            display = projection?.createVirtualDisplay(
                "AmbiX-screen", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, null
            )
            VisionState.status.set("Captura activa · modelos locales: ${VisionState.modelReady.get()}")
        } catch (error: Throwable) {
            VisionState.status.set("Error de captura: ${error.javaClass.simpleName}")
            stopCapture()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun analyze(image: Image) {
        val now = System.currentTimeMillis()
        if (now - lastProcessedAtMs < 180L) return
        lastProcessedAtMs = now
        val plane = image.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height
        val rowPixels = width + (rowStride - pixelStride * width) / pixelStride
        var blue = 0
        var purple = 0
        var samples = 0
        val stepX = (width / 160).coerceAtLeast(1)
        val stepY = (height / 280).coerceAtLeast(1)
        var y = height / 2
        while (y < height) {
            var x = 0
            while (x < width) {
                val index = (y * rowPixels + x) * pixelStride
                if (index + 2 < bytes.size) {
                    val red = bytes[index].toInt() and 255
                    val green = bytes[index + 1].toInt() and 255
                    val b = bytes[index + 2].toInt() and 255
                    if (b > red * 1.25 && b > green * 1.1 && b > 75) blue++
                    if (red > 90 && b > 90 && b > green * 1.15 && red > green * 1.1) purple++
                    samples++
                }
                x += stepX
            }
            y += stepY
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        for (row in 0 until height) {
            for (column in 0 until width) {
                val index = row * rowStride + column * pixelStride
                if (index + 2 < bytes.size) {
                    val red = bytes[index].toInt() and 255
                    val green = bytes[index + 1].toInt() and 255
                    val b = bytes[index + 2].toInt() and 255
                    val alpha = if (pixelStride >= 4) bytes[index + 3].toInt() and 255 else 255
                    pixels[row * width + column] = Color.argb(alpha, red, green, b)
                }
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        val rawArena = arenaDetector?.detect(bitmap).orEmpty()
        val rawHud = hudDetector?.detect(bitmap).orEmpty()
        bitmap.recycle()

        val capturedAt = System.currentTimeMillis()
        val arenaStable = arenaFilter.update(rawArena, capturedAt)
        val hudStable = hudFilter.update(rawHud, capturedAt)
        val allStable = arenaStable + hudStable

        val hand = hudStable.asSequence()
            .filter { it.kind == ObjectKind.CARD_IN_HAND }
            .filter { it.label.endsWith(" In Hand", true) || it.label.endsWith(" Card", true) }
            .sortedBy { it.bounds.centerX }
            .take(4)
            .mapIndexed { index, detection ->
                val name = detection.label
                    .removeSuffix(" In Hand").removeSuffix(" Card").trim()
                HandCard(name, index, detection.confidence, detection.bounds)
            }
            .toList()

        val nextCard = hudStable.firstOrNull {
            it.kind == ObjectKind.CARD_IN_HAND && it.label.endsWith(" Next", true)
        }?.label?.removeSuffix(" Next")?.trim()

        val elixir = hudStable.asSequence()
            .filter { it.kind == ObjectKind.ELIXIR }
            .mapNotNull { detection ->
                Regex("^(10|[0-9])\\s+Elixer$", RegexOption.IGNORE_CASE)
                    .find(detection.label)?.groupValues?.get(1)?.toIntOrNull()
            }
            .firstOrNull()

        val visionReady = arenaDetector?.isModelLoaded == true &&
            hudDetector?.isModelLoaded == true && elixir != null && hand.size >= 3
        val snapshot = BattleSnapshot(
            capturedAtMs = capturedAt,
            frameWidth = width,
            frameHeight = height,
            elixir = elixir,
            hand = hand,
            nextCard = nextCard,
            detections = rawArena,
            isBattleScene = rawArena.isNotEmpty(),
            visionReady = visionReady,
            diagnostic = "arena=${arenaStable.size}, hud=${hudStable.size}, mano=${hand.size}, elixir=${elixir ?: "?"}"
        )
        val plannedMove = AmbiXTacticalEngine.choose(snapshot)

        VisionState.latestDetections.set(allStable)
        VisionState.latestHand.set(hand)
        VisionState.latestElixir.set(elixir)
        VisionState.latestMove.set(plannedMove)
        VisionState.latest.set(FrameReport(width, height, samples, blue, purple, capturedAt, allStable.size))
        val modelText = if (VisionState.modelReady.get()) "detecciones estables ${allStable.size}"
            else "faltan modelos entrenados"
        VisionState.status.set(
            "Fotograma leído · ${width}×${height} · $modelText · mano ${hand.size}/4 · elixir ${elixir ?: "?"} · azul $blue · púrpura $purple"
        )
    }

    private fun stopCapture() {
        display?.release()
        display = null
        reader?.close()
        reader = null
        projection?.stop()
        projection = null
        arenaDetector?.close()
        arenaDetector = null
        hudDetector?.close()
        hudDetector = null
        processingHandler = null
        processingThread?.quitSafely()
        processingThread = null
        lastProcessedAtMs = 0L
        arenaFilter.reset()
        hudFilter.reset()
        AmbiXTacticalEngine.resetBattleMemory()
        VisionState.modelReady.set(false)
        VisionState.latestDetections.set(emptyList())
        VisionState.latestHand.set(emptyList())
        VisionState.latestElixir.set(null)
        VisionState.latestMove.set(null)
        VisionState.status.set("Captura detenida")
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Captura AmbiX", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RESULT = "capture_result"
        const val EXTRA_DATA = "capture_data"
        const val CHANNEL = "ambix_capture"
        const val HUD_MODEL_ASSET = "ambix_hud.tflite"
        const val HUD_LABELS_ASSET = "hud_labels.txt"
    }
}
