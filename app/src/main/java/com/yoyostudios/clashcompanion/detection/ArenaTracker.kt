package com.yoyostudios.clashcompanion.detection

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Lightweight temporal association for arena detections.
 *
 * This does NOT identify objects by itself. It associates detections emitted by
 * a configured object detector across frames, estimates screen-space motion,
 * and expires tracks quickly so stale positions are never treated as current.
 */
object ArenaTracker {
    enum class Lane { LEFT, CENTER, RIGHT, UNKNOWN }
    enum class Side { ENEMY, FRIENDLY, UNKNOWN }
    enum class Motion { ADVANCING, RETREATING, STATIONARY, UNKNOWN }

    data class Track(
        val id: Long,
        val className: String,
        val confidence: Float,
        val centerX: Int,
        val centerY: Int,
        val normalizedX: Float,
        val normalizedY: Float,
        val lane: Lane,
        val side: Side,
        val motion: Motion,
        val speedPerSecond: Float,
        val lastSeenMs: Long
    )

    private data class MutableTrack(
        val id: Long,
        var className: String,
        var confidence: Float,
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var seenAt: Long,
        var hits: Int
    )

    private val tracks = mutableListOf<MutableTrack>()
    private var nextId = 1L
    private const val MAX_AGE_MS = 1700L
    private const val MATCH_DISTANCE = 0.16f
    private const val MIN_CONFIDENCE = 0.35f

    @Synchronized
    fun update(
        detections: List<ArenaDetector.Detection>,
        frameWidth: Int,
        roiY: Int,
        roiHeight: Int,
        now: Long = System.currentTimeMillis()
    ): List<Track> {
        if (frameWidth <= 0 || roiHeight <= 0) return emptyList()

        val candidates = detections
            .filter { it.confidence >= MIN_CONFIDENCE }
            .map {
                Triple(
                    it,
                    (it.centerX.toFloat() / frameWidth).coerceIn(0f, 1f),
                    ((it.centerY - roiY).toFloat() / roiHeight).coerceIn(0f, 1f)
                )
            }

        tracks.removeAll { now - it.seenAt > MAX_AGE_MS }
        val used = mutableSetOf<Long>()

        for ((d, nx, ny) in candidates) {
            val match = tracks
                .asSequence()
                .filter { it.id !in used && it.className.equals(d.className, ignoreCase = true) }
                .map { track -> track to distance(track.x, track.y, nx, ny) }
                .filter { it.second <= MATCH_DISTANCE }
                .minByOrNull { it.second }
                ?.first

            if (match == null) {
                tracks.add(MutableTrack(nextId++, d.className, d.confidence, nx, ny, 0f, 0f, now, 1))
            } else {
                val dt = ((now - match.seenAt).coerceAtLeast(1L)) / 1000f
                val rawVx = (nx - match.x) / dt
                val rawVy = (ny - match.y) / dt
                match.vx = match.vx * 0.45f + rawVx * 0.55f
                match.vy = match.vy * 0.45f + rawVy * 0.55f
                match.x = nx
                match.y = ny
                match.confidence = d.confidence
                match.seenAt = now
                match.hits++
                match.className = d.className
                used.add(match.id)
            }
        }

        return tracks.mapNotNull { t ->
            val age = now - t.seenAt
            if (age > MAX_AGE_MS) return@mapNotNull null
            val lane = when {
                t.x < 0.36f -> Lane.LEFT
                t.x > 0.64f -> Lane.RIGHT
                else -> Lane.CENTER
            }
            val side = when {
                t.y < 0.46f -> Side.ENEMY
                t.y > 0.54f -> Side.FRIENDLY
                else -> Side.UNKNOWN
            }
            val motion = when {
                t.hits < 2 -> Motion.UNKNOWN
                t.vy > 0.025f -> Motion.ADVANCING
                t.vy < -0.025f -> Motion.RETREATING
                else -> Motion.STATIONARY
            }
            Track(
                id = t.id,
                className = t.className,
                confidence = t.confidence,
                centerX = (t.x * frameWidth).toInt(),
                centerY = roiY + (t.y * roiHeight).toInt(),
                normalizedX = t.x,
                normalizedY = t.y,
                lane = lane,
                side = side,
                motion = motion,
                speedPerSecond = sqrt(t.vx * t.vx + t.vy * t.vy),
                lastSeenMs = t.seenAt
            )
        }.sortedBy { it.lastSeenMs }
    }

    @Synchronized
    fun clear() {
        tracks.clear()
    }

    private fun distance(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return sqrt(dx * dx + dy * dy)
    }
}
