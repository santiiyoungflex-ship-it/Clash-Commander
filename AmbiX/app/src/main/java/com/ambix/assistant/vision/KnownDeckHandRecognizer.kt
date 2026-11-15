package com.ambix.assistant.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Conservative fallback for the user's fixed eight-card deck.
 * Uses color-histogram similarity on fixed hand slots; uncertain matches are
 * discarded. It is a bootstrap recognizer, not a substitute for a trained
 * screen-domain classifier. Automation must not treat it as verified.
 */
class KnownDeckHandRecognizer(context: Context) {
    private val names = listOf("Princess", "Prince", "Goblin Barrel", "The Log", "Guards", "Rocket", "Bandit", "Rascals")
    private val templates: Map<String, FloatArray> = names.mapNotNull { name ->
        runCatching {
            val bitmap = context.assets.open("card_templates/$name.png").use(BitmapFactory::decodeStream)
            name to histogram(bitmap)
        }.getOrNull()
    }.toMap()

    data class Match(val name: String, val confidence: Float, val bounds: Box)

    fun recognize(frame: Bitmap): List<Match> {
        if (templates.size != names.size || frame.width < 320 || frame.height < 480) return emptyList()
        val output = mutableListOf<Match>()
        // Four card slots across the lower HUD. Coordinates are normalized to
        // the captured image and intentionally conservative for portrait play.
        val centers = floatArrayOf(0.185f, 0.395f, 0.605f, 0.815f)
        for ((slot, center) in centers.withIndex()) {
            val left = (center - 0.095f).coerceIn(0f, 1f)
            val right = (center + 0.095f).coerceIn(0f, 1f)
            val top = 0.795f
            val bottom = 0.985f
            val crop = cropNormalized(frame, left, top, right, bottom) ?: continue
            val feature = histogram(crop)
            crop.recycle()
            val ranked = templates.map { (name, template) -> name to similarity(feature, template) }
                .sortedByDescending { it.second }
            val best = ranked.firstOrNull() ?: continue
            val margin = best.second - (ranked.getOrNull(1)?.second ?: 0f)
            // Histogram-only scores are not calibrated probabilities. Require
            // a strong absolute score and a clear margin; never guess.
            if (best.second >= 0.82f && margin >= 0.10f) {
                output += Match(best.first, (best.second * min(1f, margin / 0.20f)).coerceIn(0f, 0.89f),
                    Box(left, top, right, bottom))
            }
        }
        return output
    }

    private fun cropNormalized(source: Bitmap, l: Float, t: Float, r: Float, b: Float): Bitmap? {
        val x = (l * source.width).toInt().coerceIn(0, source.width - 1)
        val y = (t * source.height).toInt().coerceIn(0, source.height - 1)
        val right = (r * source.width).toInt().coerceIn(x + 1, source.width)
        val bottom = (b * source.height).toInt().coerceIn(y + 1, source.height)
        return runCatching { Bitmap.createBitmap(source, x, y, right - x, bottom - y) }.getOrNull()
    }

    private fun histogram(bitmap: Bitmap): FloatArray {
        val small = Bitmap.createScaledBitmap(bitmap, 24, 32, true)
        val bins = FloatArray(8 * 8 * 8)
        val pixels = IntArray(24 * 32)
        small.getPixels(pixels, 0, 24, 0, 0, 24, 32)
        small.recycle()
        for (pixel in pixels) {
            val red = (pixel shr 16 and 255) ushr 5
            val green = (pixel shr 8 and 255) ushr 5
            val blue = (pixel and 255) ushr 5
            bins[(red shl 6) or (green shl 3) or blue] += 1f
        }
        val total = pixels.size.toFloat()
        for (i in bins.indices) bins[i] /= total
        return bins
    }

    private fun similarity(a: FloatArray, b: FloatArray): Float {
        var distance = 0f
        for (i in a.indices) distance += abs(a[i] - b[i])
        return (1f - distance * 0.5f).coerceIn(0f, 1f)
    }
}
