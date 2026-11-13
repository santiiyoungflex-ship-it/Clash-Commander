package com.yoyostudios.clashcompanion.util

import android.content.Context
import android.util.DisplayMetrics

/**
 * Coordinates scaled relative to screen size.
 * Calibrated on Samsung Galaxy A35 (1080 x 2340) then stored as ratios
 * so they work on any device with the same aspect ratio.
 */
object Coordinates {

    // Reference device: 1080 x 2340
    private const val REF_W = 1080f
    private const val REF_H = 2340f

    private var screenW: Float = REF_W
    private var screenH: Float = REF_H

    fun init(context: Context) {
        // Gesture injection and MediaProjection use the full physical display,
        // including system bars. resources.displayMetrics can exclude those
        // areas on Android, shifting vertical taps on devices like Moto G13.
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        val display = (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
        @Suppress("DEPRECATION")
        display.getRealMetrics(dm)
        screenW = dm.widthPixels.toFloat()
        screenH = dm.heightPixels.toFloat()
    }

    private fun x(refX: Float): Float = refX / REF_W * screenW
    private fun y(refY: Float): Float = refY / REF_H * screenH
    private fun pos(refX: Float, refY: Float): Pair<Float, Float> = Pair(x(refX), y(refY))

    /** Scale a reference width dimension to actual screen pixels */
    private fun dimW(refDim: Float): Int = (refDim / REF_W * screenW).toInt()

    /** Scale a reference height dimension to actual screen pixels */
    private fun dimH(refDim: Float): Int = (refDim / REF_H * screenH).toInt()

    // ── Card Slot ROIs for classifier cropping ────────────────────────────
    // Bounding boxes for the card ART area (not the full card frame).
    // Derived from calibrated slot centers: x=350,520,690,860 at y=2130.
    // Card art crop: 140px wide × 130px tall, starting ~100px above center.
    //
    // IMPORTANT: These use RAW pixel values for the 1080x2340 screen capture
    // frame, NOT scaled via displayMetrics. ScreenCaptureService captures the
    // full resolution including nav bar, while displayMetrics excludes it.
    // Use frameScaled() variants when cropping from a captured Bitmap.

    data class CardSlotROI(val x: Int, val y: Int, val w: Int, val h: Int)

    // Reference ROI values (1080x2340 full-frame coordinates)
    // Pixel-verified from in-game screenshot analysis on Samsung A35
    private val REF_CARD_ROIS = listOf(
        CardSlotROI(248, 2025, 177, 215), // slot 0 (leftmost)
        CardSlotROI(452, 2025, 176, 215), // slot 1
        CardSlotROI(654, 2025, 177, 215), // slot 2
        CardSlotROI(858, 2025, 177, 215), // slot 3 (rightmost)
    )
    private val REF_NEXT_ROI = CardSlotROI(61, 2215, 79, 110)

    /**
     * Get card slot ROIs scaled to actual frame dimensions.
     * Call with the actual captured frame size, NOT displayMetrics.
     */
    fun getCardSlotROIs(frameW: Int, frameH: Int): List<CardSlotROI> {
        if (frameW == REF_W.toInt() && frameH == REF_H.toInt()) return REF_CARD_ROIS
        val sx = frameW / REF_W
        val sy = frameH / REF_H
        return REF_CARD_ROIS.map { roi ->
            CardSlotROI(
                (roi.x * sx).toInt(), (roi.y * sy).toInt(),
                (roi.w * sx).toInt(), (roi.h * sy).toInt()
            )
        }
    }

    /** Get next-card ROI scaled to actual frame dimensions. */
    fun getNextCardROI(frameW: Int, frameH: Int): CardSlotROI {
        if (frameW == REF_W.toInt() && frameH == REF_H.toInt()) return REF_NEXT_ROI
        val sx = frameW / REF_W
        val sy = frameH / REF_H
        return CardSlotROI(
            (REF_NEXT_ROI.x * sx).toInt(), (REF_NEXT_ROI.y * sy).toInt(),
            (REF_NEXT_ROI.w * sx).toInt(), (REF_NEXT_ROI.h * sy).toInt()
        )
    }

    /** Convenience: get ROIs using raw reference values (for 1080x2340 frames) */
    val CARD_SLOT_ROIS: List<CardSlotROI> get() = REF_CARD_ROIS
    val NEXT_CARD_ROI: CardSlotROI get() = REF_NEXT_ROI

    // ── Arena Crop Region (for Roboflow detection) ────────────────
    // Excludes top UI bar and bottom card hand area.
    // Values are raw 1080x2340 reference pixels.
    const val ARENA_CROP_TOP = 200
    const val ARENA_CROP_BOTTOM = 1900

    /** Arena crop scaled to the actual captured frame dimensions. */
    data class ArenaROI(val x: Int, val y: Int, val w: Int, val h: Int)

    fun getArenaROI(frameW: Int, frameH: Int): ArenaROI? {
        if (frameW <= 0 || frameH <= 0) return null
        val sy = frameH / REF_H
        val top = (ARENA_CROP_TOP * sy).toInt().coerceIn(0, frameH)
        val bottom = (ARENA_CROP_BOTTOM * sy).toInt().coerceIn(0, frameH)
        val height = bottom - top
        if (height <= 0) return null
        return ArenaROI(0, top, frameW, height)
    }

    // ── Card Slot Centers (bottom of screen, 4 visible cards) ──
    // Centers calibrated from the 720x1600 Moto G13 match screenshot.
    // Scaled reference x positions: 335, 540, 745, 950 on a 1080px display.
    val CARD_SLOT_1 get() = pos(335f, 2130f)
    val CARD_SLOT_2 get() = pos(540f, 2130f)
    val CARD_SLOT_3 get() = pos(745f, 2130f)
    val CARD_SLOT_4 get() = pos(950f, 2130f)

    val CARD_SLOTS get() = listOf(CARD_SLOT_1, CARD_SLOT_2, CARD_SLOT_3, CARD_SLOT_4)

    // ── Arena Zones (where to place cards) ──
    val LEFT_BRIDGE    get() = pos(240f, 950f)
    val RIGHT_BRIDGE   get() = pos(840f, 950f)
    val CENTER         get() = pos(540f, 1050f)
    val BEHIND_LEFT    get() = pos(270f, 1300f)
    val BEHIND_RIGHT   get() = pos(810f, 1300f)
    val FRONT_LEFT     get() = pos(270f, 800f)
    val FRONT_RIGHT    get() = pos(810f, 800f)
    val BACK_CENTER    get() = pos(540f, 1400f)

    // Opponent tower positions (for aggressive placement near their towers)
    val OPP_LEFT_TOWER  get() = pos(270f, 650f)
    val OPP_RIGHT_TOWER get() = pos(810f, 650f)
    val OPP_KING        get() = pos(540f, 550f)

    // Player tower positions (for defensive placement near your towers)
    val MY_LEFT_TOWER   get() = pos(270f, 1400f)
    val MY_RIGHT_TOWER  get() = pos(810f, 1400f)
    val MY_KING         get() = pos(540f, 1500f)

    // Map of zone name aliases to coordinates
    val ZONE_MAP: Map<String, Pair<Float, Float>> get() = mapOf(
        // Bridge zones
        "left_bridge" to LEFT_BRIDGE,
        "left" to LEFT_BRIDGE,
        "left bridge" to LEFT_BRIDGE,
        "bridge left" to LEFT_BRIDGE,
        "right_bridge" to RIGHT_BRIDGE,
        "right" to RIGHT_BRIDGE,
        "right bridge" to RIGHT_BRIDGE,
        "bridge right" to RIGHT_BRIDGE,
        "bridge" to LEFT_BRIDGE,
        // STT misrecognitions for "right"
        "wright" to RIGHT_BRIDGE,
        "wright bridge" to RIGHT_BRIDGE,
        "write" to RIGHT_BRIDGE,
        "write bridge" to RIGHT_BRIDGE,
        "rite" to RIGHT_BRIDGE,
        "rye" to RIGHT_BRIDGE,
        "rye tower" to OPP_RIGHT_TOWER,  // STT: "right tower" -> "rye tower"
        "rite tower" to OPP_RIGHT_TOWER,
        "aright" to RIGHT_BRIDGE,   // STT: "pekka right" -> "pek aright"
        "upright" to OPP_RIGHT_TOWER, // STT: "top right" -> "upright"
        "topright" to OPP_RIGHT_TOWER, // STT: "top right" no space
        "topleft" to OPP_LEFT_TOWER,   // STT: "top left" no space
        // STT misrecognitions for "left"
        "lift" to LEFT_BRIDGE,      // STT: "left" -> "lift"
        "loft" to LEFT_BRIDGE,
        // Center
        "center" to CENTER,
        "middle" to CENTER,
        "mid" to CENTER,
        "centre" to CENTER,
        // Behind / back (defensive)
        "behind" to BACK_CENTER,
        "behind_left" to BEHIND_LEFT,
        "back left" to BEHIND_LEFT,
        "behind left" to BEHIND_LEFT,
        "behind_right" to BEHIND_RIGHT,
        "back right" to BEHIND_RIGHT,
        "behind right" to BEHIND_RIGHT,
        "back_center" to BACK_CENTER,
        "back" to BACK_CENTER,
        "back center" to BACK_CENTER,
        "behind king" to BACK_CENTER,
        // Front (offensive)
        "front_left" to FRONT_LEFT,
        "front left" to FRONT_LEFT,
        "front_right" to FRONT_RIGHT,
        "front right" to FRONT_RIGHT,
        // Bottom (same as behind/back)
        "bottom left" to BEHIND_LEFT,
        "bottom right" to BEHIND_RIGHT,
        // Top (same as front/opponent side)
        "top left" to OPP_LEFT_TOWER,
        "top right" to OPP_RIGHT_TOWER,
        // Explicit enemy Princess Tower targets for deck-specific Barrel placement
        "opponent_left_tower" to OPP_LEFT_TOWER,
        "opponent_right_tower" to OPP_RIGHT_TOWER,
        "opponent_king" to OPP_KING,
        // Opponent towers — "left tower" defaults to ENEMY (you'd never fireball your own)
        "left tower" to OPP_LEFT_TOWER,
        "right tower" to OPP_RIGHT_TOWER,
        "his left tower" to OPP_LEFT_TOWER,
        "his right tower" to OPP_RIGHT_TOWER,
        "his king" to OPP_KING,
        "his left" to OPP_LEFT_TOWER,
        "his right" to OPP_RIGHT_TOWER,
        "enemy left" to OPP_LEFT_TOWER,
        "enemy right" to OPP_RIGHT_TOWER,
        "enemy tower" to OPP_LEFT_TOWER,
        "their king" to OPP_KING,
        "opponent king" to OPP_KING,
        // Player towers — must say "my" explicitly
        "my left tower" to MY_LEFT_TOWER,
        "my right tower" to MY_RIGHT_TOWER,
        "my king" to MY_KING,
        "my left" to MY_LEFT_TOWER,
        "my right" to MY_RIGHT_TOWER,
        // Defend — same as back left/right (defensive placement near your towers)
        "defend left" to BEHIND_LEFT,
        "defend right" to BEHIND_RIGHT,
        "defend" to BACK_CENTER,
        "defense left" to BEHIND_LEFT,
        "defense right" to BEHIND_RIGHT,
        "defense" to BACK_CENTER,
        // STT hallucinations for "defend right/left"
        "fenderite" to BEHIND_RIGHT,   // STT: "defend right" -> "fenderite"
        "fender right" to BEHIND_RIGHT,
        "fender left" to BEHIND_LEFT,
        "fender" to BACK_CENTER,
        "defened left" to BEHIND_LEFT,  // STT: "defend left" -> "defened left"
        "defened right" to BEHIND_RIGHT,
        "defended left" to BEHIND_LEFT,
        "defended right" to BEHIND_RIGHT,
        "defended back" to BACK_CENTER,
        "defended" to BACK_CENTER,
        "fund right" to BEHIND_RIGHT,  // STT: "defend right" -> "fund right"
        "fund left" to BEHIND_LEFT,
        "the fund right" to BEHIND_RIGHT, // STT: "defend right" -> "the fund right"
        "the fund left" to BEHIND_LEFT,
        "friend right" to BEHIND_RIGHT, // STT: "defend right" -> "the friend right"
        "friend left" to BEHIND_LEFT,
        "the friend right" to BEHIND_RIGHT,
        "the friend left" to BEHIND_LEFT,
        // Bare "king" = enemy king (you'd never target your own king)
        "king" to OPP_KING,
        "king tower" to OPP_KING,
    )
}
