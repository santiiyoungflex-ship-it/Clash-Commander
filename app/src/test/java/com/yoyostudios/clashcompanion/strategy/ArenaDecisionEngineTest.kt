package com.yoyostudios.clashcompanion.strategy

import com.yoyostudios.clashcompanion.detection.ArenaTracker
import org.junit.Assert.assertEquals
import org.junit.Test

class ArenaDecisionEngineTest {
    private fun track(
        confirmed: Boolean = true,
        ageMs: Long = 100L,
        side: ArenaTracker.Side = ArenaTracker.Side.ENEMY
    ) = ArenaTracker.Track(
        id = 1L,
        className = "enemy_goblin_barrel",
        confidence = 0.94f,
        centerX = 200,
        centerY = 275,
        normalizedX = 0.2f,
        normalizedY = 0.35f,
        lane = ArenaTracker.Lane.LEFT,
        side = side,
        motion = ArenaTracker.Motion.ADVANCING,
        speedPerSecond = 0.1f,
        lastSeenMs = 1000L,
        ageMs = ageMs,
        hits = if (confirmed) 2 else 1,
        confirmed = confirmed
    )

    @Test fun confirmedEnemyBarrelCanSelectLog() {
        val result = ArenaDecisionEngine.evaluate(
            listOf(track()), setOf("The Log"), 100L, 5, 1100L
        )
        assertEquals(TacticalPolicy.Action.PLAY, result.action)
        assertEquals("The Log", result.card)
        assertEquals(TacticalPolicy.Lane.LEFT, result.lane)
    }

    @Test fun unconfirmedTrackCannotTriggerAction() {
        val result = ArenaDecisionEngine.evaluate(
            listOf(track(confirmed = false)), setOf("The Log"), 100L, 5, 1100L
        )
        assertEquals(TacticalPolicy.Action.WAIT, result.action)
    }

    @Test fun staleTrackCannotTriggerAction() {
        val result = ArenaDecisionEngine.evaluate(
            listOf(track(ageMs = 0L)), setOf("The Log"), 100L, 5, 1701L
        )
        assertEquals(TacticalPolicy.Action.WAIT, result.action)
    }

    @Test fun friendlyTrackCannotTriggerEnemyDefense() {
        val result = ArenaDecisionEngine.evaluate(
            listOf(track(side = ArenaTracker.Side.FRIENDLY)), setOf("The Log"), 100L, 5, 1100L
        )
        assertEquals(TacticalPolicy.Action.WAIT, result.action)
    }
}
