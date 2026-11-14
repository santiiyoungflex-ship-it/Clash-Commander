package com.yoyostudios.clashcompanion.strategy

import com.yoyostudios.clashcompanion.detection.ArenaTracker

/**
 * Converts temporally confirmed vision tracks into a conservative tactical decision.
 * This is analysis-only: it does not tap cards or bypass the manual STOP control.
 */
object ArenaDecisionEngine {
    fun evaluate(
        tracks: List<ArenaTracker.Track>,
        hand: Set<String>,
        handAgeMs: Long?,
        elixir: Int?,
        nowMs: Long = System.currentTimeMillis()
    ): TacticalPolicy.Decision {
        val threats = tracks.asSequence()
            .filter { it.confirmed && nowMs - it.lastSeenMs in 0..700L }
            .map {
                TacticalPolicy.Threat(
                    className = it.className.removePrefix("enemy_"),
                    confidence = it.confidence,
                    side = when (it.side) {
                        ArenaTracker.Side.ENEMY -> TacticalPolicy.Side.ENEMY
                        ArenaTracker.Side.FRIENDLY -> TacticalPolicy.Side.FRIENDLY
                        ArenaTracker.Side.UNKNOWN -> TacticalPolicy.Side.UNKNOWN
                    },
                    lane = when (it.lane) {
                        ArenaTracker.Lane.LEFT -> TacticalPolicy.Lane.LEFT
                        ArenaTracker.Lane.CENTER -> TacticalPolicy.Lane.CENTER
                        ArenaTracker.Lane.RIGHT -> TacticalPolicy.Lane.RIGHT
                        ArenaTracker.Lane.UNKNOWN -> TacticalPolicy.Lane.UNKNOWN
                    },
                    normalizedY = it.normalizedY,
                    ageMs = nowMs - it.lastSeenMs
                )
            }
            .toList()

        return TacticalPolicy.decide(
            TacticalPolicy.Snapshot(
                threats = threats,
                hand = hand,
                handAgeMs = handAgeMs,
                elixir = elixir
            )
        )
    }
}
