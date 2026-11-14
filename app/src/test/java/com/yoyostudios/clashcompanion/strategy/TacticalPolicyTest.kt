package com.yoyostudios.clashcompanion.strategy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TacticalPolicyTest {

    private fun snapshot(
        threat: TacticalPolicy.Threat = TacticalPolicy.Threat(
            className = "Goblin Barrel",
            confidence = 0.94f,
            side = TacticalPolicy.Side.ENEMY,
            lane = TacticalPolicy.Lane.LEFT,
            normalizedY = 0.35f,
            ageMs = 120L
        ),
        hand: Set<String> = setOf("The Log", "Princess", "Prince", "Rocket"),
        handAgeMs: Long? = 100L,
        elixir: Int? = 5
    ) = TacticalPolicy.Snapshot(listOf(threat), hand, handAgeMs, elixir)

    @Test fun exactFreshThreatWithLogProducesDefensivePlay() {
        val decision = TacticalPolicy.decide(snapshot())
        assertEquals(TacticalPolicy.Action.PLAY, decision.action)
        assertEquals("The Log", decision.card)
        assertEquals(TacticalPolicy.Lane.LEFT, decision.lane)
    }

    @Test fun staleThreatNeverProducesAction() {
        val threat = snapshot().threats.single().copy(ageMs = 701L)
        assertEquals(TacticalPolicy.Action.WAIT, TacticalPolicy.decide(snapshot(threat)).action)
    }

    @Test fun uncertainDetectionNeverProducesAction() {
        val threat = snapshot().threats.single().copy(confidence = 0.77f)
        assertEquals(TacticalPolicy.Action.WAIT, TacticalPolicy.decide(snapshot(threat)).action)
    }

    @Test fun friendlyUnitNeverTriggersDefense() {
        val threat = snapshot().threats.single().copy(side = TacticalPolicy.Side.FRIENDLY)
        assertEquals(TacticalPolicy.Action.WAIT, TacticalPolicy.decide(snapshot(threat)).action)
    }

    @Test fun unknownHandNeverProducesAction() {
        assertEquals(TacticalPolicy.Action.WAIT, TacticalPolicy.decide(snapshot(handAgeMs = null)).action)
    }

    @Test fun staleHandNeverProducesAction() {
        assertEquals(TacticalPolicy.Action.WAIT, TacticalPolicy.decide(snapshot(handAgeMs = 651L)).action)
    }

    @Test fun futureDatedHandNeverProducesAction() {
        assertEquals(TacticalPolicy.Action.WAIT, TacticalPolicy.decide(snapshot(handAgeMs = -1L)).action)
    }

    @Test fun unknownElixirNeverProducesAction() {
        assertEquals(TacticalPolicy.Action.WAIT, TacticalPolicy.decide(snapshot(elixir = null)).action)
    }

    @Test fun missingCounterNeverProducesAction() {
        val decision = TacticalPolicy.decide(snapshot(hand = setOf("Princess", "Prince")))
        assertEquals(TacticalPolicy.Action.WAIT, decision.action)
        assertNull(decision.card)
    }

    @Test fun unvalidatedThreatClassNeverProducesAction() {
        val threat = snapshot().threats.single().copy(className = "Hog Rider")
        assertEquals(TacticalPolicy.Action.WAIT, TacticalPolicy.decide(snapshot(threat)).action)
    }

    @Test fun invalidElixirNeverProducesAction() {
        assertEquals(TacticalPolicy.Action.WAIT, TacticalPolicy.decide(snapshot(elixir = 11)).action)
    }
}
