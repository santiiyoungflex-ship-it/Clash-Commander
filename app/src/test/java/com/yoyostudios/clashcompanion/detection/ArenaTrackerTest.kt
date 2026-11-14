package com.yoyostudios.clashcompanion.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaTrackerTest {
    private fun detection(x: Int, y: Int = 250, name: String = "enemy_goblin", confidence: Float = 0.9f) =
        ArenaDetector.Detection(name, confidence, x, y, 30, 30, 1000L)

    @Test fun firstObservationIsNotConfirmed() {
        ArenaTracker.clear()
        val tracks = ArenaTracker.update(listOf(detection(200)), 1000, 100, 500, 1000L)
        assertEquals(1, tracks.size)
        assertFalse(tracks.single().confirmed)
        assertEquals(1, tracks.single().hits)
    }

    @Test fun secondFrameConfirmsTrack() {
        ArenaTracker.clear()
        ArenaTracker.update(listOf(detection(200)), 1000, 100, 500, 1000L)
        val tracks = ArenaTracker.update(listOf(detection(205)), 1000, 100, 500, 1100L)
        assertEquals(1, tracks.size)
        assertTrue(tracks.single().confirmed)
        assertEquals(2, tracks.single().hits)
        assertEquals(0L, tracks.single().ageMs)
    }

    @Test fun twoNearbySameClassUnitsRemainSeparateInOneFrame() {
        ArenaTracker.clear()
        val tracks = ArenaTracker.update(
            listOf(detection(200), detection(215)),
            1000, 100, 500, 1000L
        )
        assertEquals(2, tracks.size)
        assertNotEquals(tracks[0].id, tracks[1].id)
    }

    @Test fun staleTracksExpire() {
        ArenaTracker.clear()
        ArenaTracker.update(listOf(detection(200)), 1000, 100, 500, 1000L)
        val tracks = ArenaTracker.update(emptyList(), 1000, 100, 500, 2101L)
        assertTrue(tracks.isEmpty())
    }

    @Test fun invalidConfidenceIsRejected() {
        ArenaTracker.clear()
        val tracks = ArenaTracker.update(
            listOf(detection(200, confidence = Float.NaN), detection(300, confidence = 1.1f)),
            1000, 100, 500, 1000L
        )
        assertTrue(tracks.isEmpty())
    }

    @Test fun futureTimestampDoesNotCreateNegativeAgeTrack() {
        ArenaTracker.clear()
        ArenaTracker.update(listOf(detection(200)), 1000, 100, 500, 1000L)
        val tracks = ArenaTracker.update(emptyList(), 1000, 100, 500, 900L)
        assertTrue(tracks.isEmpty())
    }
}
