package com.ambix.assistant.vision

import kotlin.math.max
import kotlin.math.min

/**
 * Temporal consensus filter for raw model detections.
 *
 * A detection is exposed to strategy only after it has been observed in
 * several distinct frames. This reduces one-frame hallucinations without
 * pretending that confidence scores are calibrated probabilities.
 */
class TemporalDetectionFilter(
    private val windowSize: Int = 5,
    private val minHits: Int = 3,
    private val maxMissedFrames: Int = 1,
    private val minIou: Float = 0.30f
) {
    private data class Track(
        val id: Long,
        val label: String,
        val kind: ObjectKind,
        val team: Team,
        var bounds: Box,
        var emaConfidence: Float,
        val hitFrames: ArrayDeque<Int> = ArrayDeque(),
        var lastFrame: Int
    )

    private val tracks = mutableListOf<Track>()
    private var frameIndex = 0
    private var nextId = 1L

    init {
        require(windowSize >= 1)
        require(minHits in 1..windowSize)
        require(maxMissedFrames >= 0)
        require(minIou in 0f..1f)
    }

    @Synchronized
    fun update(raw: List<Detection>): List<Detection> {
        frameIndex++
        val candidates = suppressDuplicates(raw)
            .filter { it.confidence >= admissionThreshold(it.kind) }
        val usedTrackIds = mutableSetOf<Long>()

        for (detection in candidates) {
            val match = tracks.asSequence()
                .filter { it.id !in usedTrackIds }
                .filter { it.label.equals(detection.label, ignoreCase = true) }
                .filter { it.kind == detection.kind && it.team == detection.team }
                .filter { frameIndex - it.lastFrame <= maxMissedFrames + 1 }
                .map { it to iou(it.bounds, detection.bounds) }
                .filter { it.second >= minIou }
                .maxByOrNull { it.second }
                ?.first

            if (match == null) {
                tracks += Track(
                    id = nextId++,
                    label = detection.label,
                    kind = detection.kind,
                    team = detection.team,
                    bounds = detection.bounds,
                    emaConfidence = detection.confidence,
                    hitFrames = ArrayDeque<Int>().apply { addLast(frameIndex) },
                    lastFrame = frameIndex
                )
            } else {
                usedTrackIds += match.id
                match.bounds = detection.bounds
                match.emaConfidence = 0.65f * detection.confidence + 0.35f * match.emaConfidence
                match.lastFrame = frameIndex
                match.hitFrames.addLast(frameIndex)
            }
        }

        val oldestAllowed = frameIndex - windowSize + 1
        tracks.forEach { track ->
            while (track.hitFrames.isNotEmpty() && track.hitFrames.first() < oldestAllowed) {
                track.hitFrames.removeFirst()
            }
        }
        tracks.removeAll { frameIndex - it.lastFrame > windowSize }

        return tracks.asSequence()
            .filter { it.hitFrames.size >= minHits }
            .filter { it.lastFrame == frameIndex }
            .map { track ->
                Detection(
                    label = track.label,
                    kind = track.kind,
                    team = track.team,
                    bounds = track.bounds,
                    confidence = track.emaConfidence.coerceIn(0f, 1f)
                )
            }
            .toList()
    }

    @Synchronized
    fun reset() {
        tracks.clear()
        frameIndex = 0
        nextId = 1L
    }

    private fun suppressDuplicates(input: List<Detection>): List<Detection> {
        val kept = mutableListOf<Detection>()
        input.sortedByDescending { it.confidence }.forEach { candidate ->
            val duplicate = kept.any { existing ->
                existing.label.equals(candidate.label, ignoreCase = true) &&
                    existing.kind == candidate.kind &&
                    existing.team == candidate.team &&
                    iou(existing.bounds, candidate.bounds) >= 0.55f
            }
            if (!duplicate) kept += candidate
        }
        return kept
    }

    private fun admissionThreshold(kind: ObjectKind): Float = when (kind) {
        ObjectKind.TROOP, ObjectKind.BUILDING -> 0.78f
        ObjectKind.TOWER -> 0.82f
        ObjectKind.SPELL -> 0.90f
        ObjectKind.CARD_IN_HAND -> 0.94f
        ObjectKind.ELIXIR -> 0.96f
        ObjectKind.UNKNOWN -> 0.99f
    }

    private fun iou(a: Box, b: Box): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        val union = areaA + areaB - intersection
        return if (union <= 0f) 0f else intersection / union
    }
}
