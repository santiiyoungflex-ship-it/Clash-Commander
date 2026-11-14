package com.ambix.assistant.vision

import android.graphics.Bitmap

/**
 * Coordinates are normalized to the captured frame (0f..1f), so the model is
 * independent of phone resolution and screen density.
 */
data class Box(
    val left: Float, val top: Float, val right: Float, val bottom: Float
) {
    init {
        require(left in 0f..1f && top in 0f..1f)
        require(right in 0f..1f && bottom in 0f..1f)
        require(right >= left && bottom >= top)
    }
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

enum class Team { PLAYER, OPPONENT, UNKNOWN }
enum class ObjectKind { TROOP, BUILDING, TOWER, SPELL, CARD_IN_HAND, ELIXIR, UNKNOWN }

data class Detection(
    val label: String,
    val kind: ObjectKind,
    val team: Team,
    val bounds: Box,
    val confidence: Float
) {
    init { require(confidence in 0f..1f) }
}

data class HandCard(
    val name: String?,
    val slot: Int,
    val confidence: Float,
    val bounds: Box
) {
    init {
        require(slot in 0..3)
        require(confidence in 0f..1f)
    }
}

data class BattleSnapshot(
    val capturedAtMs: Long,
    val frameWidth: Int,
    val frameHeight: Int,
    val elixir: Int?,
    val hand: List<HandCard>,
    val nextCard: String?,
    val detections: List<Detection>,
    val isBattleScene: Boolean,
    val visionReady: Boolean,
    val diagnostic: String
) {
    init {
        require(frameWidth > 0 && frameHeight > 0)
        require(elixir == null || elixir in 0..10)
        require(hand.map { it.slot }.distinct().size == hand.size)
    }

    companion object {
        fun unavailable(width: Int, height: Int, reason: String) = BattleSnapshot(
            capturedAtMs = System.currentTimeMillis(),
            frameWidth = width.coerceAtLeast(1),
            frameHeight = height.coerceAtLeast(1),
            elixir = null,
            hand = emptyList(),
            nextCard = null,
            detections = emptyList(),
            isBattleScene = false,
            visionReady = false,
            diagnostic = reason
        )
    }
}

/** Implemented by the local TFLite detector once its trained model is bundled. */
interface BattleObjectDetector {
    fun detect(frame: Bitmap): List<Detection>
    val isModelLoaded: Boolean
    val diagnostic: String
    fun close() {}
}

/**
 * Keeps opponent card observations across frames. Repeated detections of the
 * same card are deduplicated; cycle estimates are never treated as certainty.
 */
class OpponentCycleMemory(private val maxHistory: Int = 16) {
    private val played = ArrayDeque<String>()

    @Synchronized
    fun record(cardName: String) {
        val normalized = cardName.trim()
        if (normalized.isEmpty()) return
        if (played.lastOrNull() == normalized) return
        played.addLast(normalized)
        while (played.size > maxHistory) played.removeFirst()
    }

    @Synchronized
    fun recent(): List<String> = played.toList()

    @Synchronized
    fun likelyInCycle(): List<String> {
        val unique = LinkedHashSet<String>()
        played.asReversed().forEach { unique.add(it) }
        return unique.toList()
    }

    @Synchronized
    fun reset() = played.clear()
}
