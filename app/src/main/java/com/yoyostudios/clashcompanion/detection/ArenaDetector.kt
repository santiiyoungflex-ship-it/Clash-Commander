package com.yoyostudios.clashcompanion.detection

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.yoyostudios.clashcompanion.api.RoboflowClient
import com.yoyostudios.clashcompanion.capture.ScreenCaptureService
import com.yoyostudios.clashcompanion.util.Coordinates
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

/**
 * Background arena detection using Roboflow hosted inference API.
 *
 * Polls the arena at ~1-2 FPS, sends cropped screenshots to Roboflow,
 * and maintains a cache of detected enemy troops with positions.
 *
 * Used by:
 *  - CommandRouter.checkRules() — conditional rule triggers
 *  - CommandRouter.executeTargetingPath() — spell placement on detected troops
 *  - CommandRouter.buildArenaSection() — context for Smart Path / Autopilot
 */
object ArenaDetector {

    private const val TAG = "ClashCompanion"

    /** Polling interval between Roboflow API calls */
    private const val POLL_INTERVAL_MS = 800L

    /** Maximum age of a detection before pruning (3 seconds) */
    private const val DETECTION_MAX_AGE_MS = 3000L

    /** JPEG compression quality for Roboflow upload (lower = faster) */
    private const val JPEG_QUALITY = 70

    // ── Data ──────────────────────────────────────────────────────────

    /** A detected troop/building on the arena */
    data class Detection(
        val className: String,
        val confidence: Float,
        val centerX: Int,
        val centerY: Int,
        val width: Int,
        val height: Int,
        val timestamp: Long = System.currentTimeMillis()
    )

    /** Current arena detections. Thread-safe via @Volatile + immutable list swap. */
    @Volatile
    var currentDetections: List<Detection> = emptyList()
        private set

    /** Temporal tracks derived from successive detector outputs. */
    @Volatile
    var currentTracks: List<ArenaTracker.Track> = emptyList()
        private set

    /** Whether arena polling is active */
    var isPolling = false
        private set

    private var pollJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ── Public API ────────────────────────────────────────────────────

    /**
     * Start background arena polling.
     *
     * @param onDetectionsChanged Callback when new detections arrive (called on Default dispatcher)
     */
    fun startPolling(onDetectionsChanged: ((List<Detection>) -> Unit)? = null) {
        if (pollJob?.isActive == true) {
            Log.w(TAG, "ARENA: Polling already active")
            return
        }

        isPolling = true
        Log.i(TAG, "ARENA: Starting Roboflow polling at ~${1000 / POLL_INTERVAL_MS} FPS")

        pollJob = scope.launch {
            var consecutiveErrors = 0

            while (isActive) {
                // Do not relabel a cached/frozen screenshot as a fresh detector result.
                // A stale capture clears tracks so downstream policy cannot act on it.
                if (!ScreenCaptureService.hasFreshFrame(maxAgeMs = 600L)) {
                    currentDetections = emptyList()
                    currentTracks = emptyList()
                    ArenaTracker.clear()
                    delay(POLL_INTERVAL_MS)
                    continue
                }

                val frame = ScreenCaptureService.getLatestFrame()
                if (frame == null || frame.isRecycled) {
                    delay(POLL_INTERVAL_MS)
                    continue
                }

                // Safe copy to prevent bitmap recycling crash
                val safeCopy = try {
                    frame.copy(Bitmap.Config.ARGB_8888, false)
                } catch (e: Exception) {
                    delay(POLL_INTERVAL_MS)
                    continue
                }
                if (safeCopy == null) { delay(POLL_INTERVAL_MS); continue }
                if (!ScreenCaptureService.hasFreshFrame(maxAgeMs = 600L)) {
                    safeCopy.recycle()
                    currentDetections = emptyList()
                    currentTracks = emptyList()
                    ArenaTracker.clear()
                    delay(POLL_INTERVAL_MS)
                    continue
                }

                try {
                    val capturedWidth = safeCopy.width
                    val capturedHeight = safeCopy.height
                    // Compute the scaled crop while the source bitmap is still valid.
                    val arenaRoi = Coordinates.getArenaROI(capturedWidth, capturedHeight)
                    val arenaCrop = arenaRoi?.let {
                        Bitmap.createBitmap(safeCopy, it.x, it.y, it.w, it.h)
                    }
                    safeCopy.recycle()

                    if (arenaCrop == null) {
                        delay(POLL_INTERVAL_MS)
                        continue
                    }

                    // Convert to base64 JPEG
                    val base64 = bitmapToBase64(arenaCrop)
                    arenaCrop.recycle()

                    // Call Roboflow API
                    val roboDetections = RoboflowClient.detect(base64)

                    consecutiveErrors = 0

                    // Roboflow coordinates are relative to the cropped bitmap.
                    // Translate Y back into full-frame coordinates using the scaled ROI.
                    val roi = arenaRoi
                    val newDetections = if (roi == null) emptyList() else roboDetections.map { d ->
                        Detection(
                            className = d.className,
                            confidence = d.confidence,
                            centerX = d.centerX,
                            centerY = d.centerY + roi.y,
                            width = d.width,
                            height = d.height
                        )
                    }

                    // Never carry forward old boxes as if they were current positions.
                    // Empty/failed frames must clear stale threats instead of steering a defense.
                    currentDetections = newDetections
                    currentTracks = if (roi == null) emptyList() else ArenaTracker.update(
                        newDetections,
                        roi.w,
                        roi.y,
                        roi.h
                    )
                    val merged = newDetections

                    if (newDetections.isNotEmpty()) {
                        val summary = newDetections.joinToString(", ") {
                            "${it.className}(${it.centerX},${it.centerY})"
                        }
                        Log.i(TAG, "ARENA: ${newDetections.size} detected: $summary")
                    }

                    onDetectionsChanged?.invoke(merged)
                } catch (e: Exception) {
                    safeCopy.recycle()
                    consecutiveErrors++
                    Log.w(TAG, "ARENA: Poll error ($consecutiveErrors): ${e.message}")

                    // Back off on repeated errors
                    if (consecutiveErrors > 5) {
                        Log.e(TAG, "ARENA: Too many errors, pausing 10s")
                        delay(10_000)
                        consecutiveErrors = 0
                    }
                }

                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Stop arena polling.
     */
    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        isPolling = false
        RoboflowClient.resetFreshness()
        currentDetections = emptyList()
        currentTracks = emptyList()
        ArenaTracker.clear()
        Log.i(TAG, "ARENA: Polling stopped")
    }

    /**
     * Clean up resources.
     */
    fun destroy() {
        stopPolling()
        scope.cancel()
    }

    // ── Internals ─────────────────────────────────────────────────────

    /**
     * Crop the arena region from a full-screen capture.
     * Excludes: top UI bar (king towers/elixir) and bottom card hand.
     */
    private fun cropArena(frame: Bitmap): Bitmap? {
        val roi = Coordinates.getArenaROI(frame.width, frame.height) ?: return null

        return try {
            Bitmap.createBitmap(frame, roi.x, roi.y, roi.w, roi.h)
        } catch (e: Exception) {
            Log.w(TAG, "ARENA: Crop failed: ${e.message}")
            null
        }
    }

    /**
     * Convert a Bitmap to a base64-encoded JPEG string for Roboflow API.
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }
}
