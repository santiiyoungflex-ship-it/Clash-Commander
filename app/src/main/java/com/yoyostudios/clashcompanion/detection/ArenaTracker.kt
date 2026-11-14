package com.yoyostudios.clashcompanion.detection

import kotlin.math.sqrt

/**
 * Lightweight temporal association for detections emitted by an object detector.
 * It never identifies objects itself. A track is marked confirmed only after
 * at least two distinct frames; consumers must reject unconfirmed/stale tracks.
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
        val lastSeenMs: Long,
        val ageMs: Long,
        val hits: Int,
        val confirmed: Boolean
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
    private const val MAX_AGE_MS = 1100L
    private const val MATCH_DISTANCE = 0.16f
    private const val MIN_CONFIDENCE = 0.35f
    private const val MIN_CONFIRMATION_HITS = 2

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
            .filter { it.confidence.isFinite() && it.confidence in MIN_CONFIDENCE..1f }
            .map {
                Triple(
                    it,
                    (it.centerX.toFloat() / frameWidth).coerceIn(0f, 1f),
                    ((it.centerY - roiY).toFloat() / roiHeight).coerceIn(0f, 1f)
                )
            }
            .sortedByDescending { it.first.confidence }

        // Reject clock anomalies rather than allowing negative track ages.
        tracks.removeAll { now < it.seenAt || now - it.seenAt > MAX_AGE_MS }
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
                val created = MutableTrack(nextId++, d.className, d.confidence, nx, ny, 0f, 0f, now, 1)
                tracks.add(created)
                // A new track belongs to this frame and cannot absorb another detection
                // from the same frame. This prevents two nearby units collapsing into one.
                used.add(created.id)
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
            if (age !in 0..MAX_AGE_MS) return@mapNotNull null
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
                t.hits < MIN_CONFIRMATION_HITS -> Motion.UNKNOWN
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
                lastSeenMs = t.seenAt,
                ageMs = age,
                hits = t.hits,
                confirmed = t.hits >= MIN_CONFIRMATION_HITS
            )
        }.sortedBy { it.lastSeenMs }
    }

    @Synchronized
    fun clear() {
        tracks.clear()
        nextId = 1L
    }

    private fun distance(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return sqrt(dx * dx + dy * dy)
    }
}
