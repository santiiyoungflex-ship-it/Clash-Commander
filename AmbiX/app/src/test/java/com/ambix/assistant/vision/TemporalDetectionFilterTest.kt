package com.ambix.assistant.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporalDetectionFilterTest {
    private fun hog(confidence: Float = 0.93f) = Detection(
        label = "Hog Rider",
        kind = ObjectKind.TROOP,
        team = Team.OPPONENT,
        bounds = Box(0.40f, 0.35f, 0.58f, 0.55f),
        confidence = confidence
    )

    @Test
    fun oneFrameFalsePositiveIsNotReleased() {
        val filter = TemporalDetectionFilter()
        assertTrue(filter.update(listOf(hog()), 100L).isEmpty())
        assertTrue(filter.update(emptyList(), 133L).isEmpty())
        assertTrue(filter.update(emptyList(), 166L).isEmpty())
    }

    @Test
    fun sameObjectMustBeSeenInThreeDistinctFrames() {
        val filter = TemporalDetectionFilter()
        assertTrue(filter.update(listOf(hog()), 100L).isEmpty())
        assertTrue(filter.update(listOf(hog(0.95f)), 133L).isEmpty())
        val stable = filter.update(listOf(hog(0.94f)), 166L)
        assertEquals(1, stable.size)
        assertEquals("Hog Rider", stable.single().label)
    }

    @Test
    fun repeatedCallsForSameTimestampDoNotCountAsNewFrames() {
        val filter = TemporalDetectionFilter()
        repeat(5) { assertTrue(filter.update(listOf(hog()), 100L).isEmpty()) }
        assertTrue(filter.update(listOf(hog()), 133L).isEmpty())
        assertEquals(1, filter.update(listOf(hog()), 166L).size)
    }
}
