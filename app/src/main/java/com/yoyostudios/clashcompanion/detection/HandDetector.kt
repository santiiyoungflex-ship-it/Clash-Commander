package com.yoyostudios.clashcompanion.detection

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.yoyostudios.clashcompanion.capture.ScreenCaptureService
import com.yoyostudios.clashcompanion.util.Coordinates
import kotlinx.coroutines.*

/**
 * Hand detection using on-device ResNet card classifier.
 *
 * Scans the 4 visible hand card slots + 1 next-card slot at ~5 FPS.
 * Each scan classifies all 5 slots via CardClassifier (~5ms total).
 * Results cached in [currentHand] for instant lookup by CommandRouter.
 *
 * Uses YOLOv8n-cls classifier via CardClassifier for real-time inference.
 */
object HandDetector {

    private const val TAG = "ClashCompanion"
    private const val SCAN_INTERVAL_MS = 200L // ~5 FPS

    // ── State ───────────────────────────────────────────────────────────

    /**
     * Current hand state: slotIndex → cardName (DeckManager format).
     * Slots 0-3 = hand cards (left to right), slot 4 = next card.
     * Thread-safe via @Volatile + immutable map swap.
     */
    @Volatile
    var currentHand: Map<Int, String> = emptyMap()
        private set

    /** Timestamp of the last successful five-slot scan; consumers must enforce freshness. */
    @Volatile
    var lastScanAtMs: Long = 0L
        private set

    val handAgeMs: Long?
        get() = lastScanAtMs.takeIf { it > 0L }?.let { timestamp ->
            val now = System.currentTimeMillis()
            if (now >= timestamp) now - timestamp else null
        }

    /** Convenience: cardName → slotIndex (hand slots 0-3 only) */
    val cardToSlot: Map<String, Int>
        get() = currentHand.filterKeys { it < 4 }
            .entries.associate { (slot, card) -> card to slot }

    /** The next card waiting in queue (slot 4), or null */
    val nextCard: String?
        get() = currentHand[4]

    /** Whether the classifier model is loaded */
    val isCalibrated: Boolean
        get() = CardClassifier.isReady

    /** Number of known cards (always 8 once deck is loaded) */
    val templateCount: Int
        get() = if (CardClassifier.isReady) 8 else 0

    /** Names of all cards the model can detect */
    val calibratedCards: Set<String>
        get() = currentHand.values.toSet()

    private var scanJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var consecutiveNullFrames = 0

    // ── Background Scanner ──────────────────────────────────────────────

    /**
     * Start continuous hand scanning in background at ~5 FPS.
     * Updates [currentHand] and [cardToSlot] automatically.
     *
     * @param deckCards The 8 deck card names to match against
     * @param context Android context (unused, kept for API compat)
     * @param onHandChanged Optional callback when hand state changes
     */
    fun startScanning(
        deckCards: List<String>,
        context: Context,
        onHandChanged: ((Map<Int, String>) -> Unit)? = null
    ) {
        if (scanJob?.isActive == true) {
            Log.w(TAG, "CARD-ML: Scanning already active")
            return
        }

        consecutiveNullFrames = 0
        Log.i(TAG, "CARD-ML: Starting background scan at ${1000 / SCAN_INTERVAL_MS} FPS")

        scanJob = scope.launch {
            var lastHand = mapOf<Int, String>()
            val slotMissCount = mutableMapOf<Int, Int>() // slot -> consecutive scans with no detection

            while (isActive) {
                // A repeated/frozen screenshot must not refresh the hand timestamp.
                // Keep the source-frame timestamp, not inference completion time.
                if (!ScreenCaptureService.hasFreshFrame(maxAgeMs = 600L)) {
                    consecutiveNullFrames++
                    if (consecutiveNullFrames == 10) {
                        Log.e(TAG, "CARD-ML: Screen capture stale — refusing to refresh hand state")
                    }
                    delay(SCAN_INTERVAL_MS)
                    continue
                }
                val sourceFrameTimestampMs = ScreenCaptureService.latestFrameTimestampMs()
                val frame = ScreenCaptureService.getLatestFrame()
                if (frame == null || frame.isRecycled) {
                    consecutiveNullFrames++
                    if (consecutiveNullFrames == 10) {
                        Log.e(TAG, "CARD-ML: Screen capture appears dead — no frames for ${10 * SCAN_INTERVAL_MS}ms")
                    }
                    delay(SCAN_INTERVAL_MS)
                    continue
                }
                consecutiveNullFrames = 0

                // Safe copy to prevent bitmap recycling crash
                val safeCopy = try {
                    frame.copy(Bitmap.Config.ARGB_8888, false)
                } catch (e: Exception) {
                    Log.w(TAG, "CARD-ML: Frame copy failed: ${e.message}")
                    delay(SCAN_INTERVAL_MS)
                    continue
                }
                if (safeCopy == null) { delay(SCAN_INTERVAL_MS); continue }
                if (!ScreenCaptureService.hasFreshFrame(maxAgeMs = 600L) ||
                    ScreenCaptureService.latestFrameTimestampMs() != sourceFrameTimestampMs) {
                    safeCopy.recycle()
                    delay(SCAN_INTERVAL_MS)
                    continue
                }

                try {
                    // Always classify fresh frames. A brightness gate based on fixed
                    // Samsung screen coordinates prevented scanning on other devices
                    // (including Motorola) before the card classifier could run.
                    // Classify all 5 card slots via YOLOv8n-cls
                    val scanStart = System.currentTimeMillis()
                    val newScan = CardClassifier.classifyHand(safeCopy, deckCards)
                    val scanMs = System.currentTimeMillis() - scanStart

                    // Temporal smoothing with decay: keep old value for brief dips
                    // (dimmed cards, animations) but drop after 5 consecutive misses (~1s).
                    val merged = lastHand.toMutableMap()
                    for (slot in 0..4) {
                        val newCard = newScan[slot]
                        if (newCard != null) {
                            merged[slot] = newCard
                            slotMissCount[slot] = 0
                        } else {
                            val misses = (slotMissCount[slot] ?: 0) + 1
                            slotMissCount[slot] = misses
                            if (misses > 5) {
                                // Card unrecognized for >1 second — remove from hand
                                merged.remove(slot)
                            }
                            // If misses <= 5, keep lastHand value (grace period)
                        }
                    }
                    // If a card appears in a DIFFERENT slot now, remove it from old slot
                    // (handles card cycling after play)
                    val cardSlots = mutableMapOf<String, Int>()
                    for ((slot, card) in merged) {
                        val existing = cardSlots[card]
                        if (existing != null && newScan.containsKey(slot)) {
                            if (!newScan.containsKey(existing)) {
                                merged.remove(existing)
                            }
                        }
                        cardSlots[card] = slot
                    }

                    val hand = merged.toMap()

                    // Publish the timestamp of the exact source frame classified.
                    // Never make a frozen frame appear fresh just because inference completed.
                    if (!ScreenCaptureService.hasFreshFrame(maxAgeMs = 600L) ||
                        ScreenCaptureService.latestFrameTimestampMs() != sourceFrameTimestampMs) {
                        delay(SCAN_INTERVAL_MS)
                        continue
                    }
                    currentHand = hand
                    lastScanAtMs = sourceFrameTimestampMs

                    if (hand != lastHand) {
                        lastHand = hand.toMap()
                        onHandChanged?.invoke(hand)
                        if (hand.isNotEmpty()) {
                            val handStr = (0..3).map { hand[it] ?: "?" }.joinToString(" | ")
                            val nextStr = hand[4] ?: "?"
                            Log.i(TAG, "CARD-ML: Hand: $handStr | Next: $nextStr (${scanMs}ms)")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "CARD-ML: Scan error: ${e.message}")
                } finally {
                    safeCopy.recycle()
                }

                delay(SCAN_INTERVAL_MS)
            }
        }
    }

    /** Stop background scanning. */
    fun stopScanning() {
        scanJob?.cancel()
        scanJob = null
        lastScanAtMs = 0L
        currentHand = emptyMap()
        Log.i(TAG, "CARD-ML: Scanning stopped")
    }

    /** Whether background scanning is active */
    val isScanning: Boolean
        get() = scanJob?.isActive == true

    /** Clean up resources */
    fun destroy() {
        stopScanning()
        scope.cancel()
    }
}
