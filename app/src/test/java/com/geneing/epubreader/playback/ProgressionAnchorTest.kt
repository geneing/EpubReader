package com.geneing.epubreader.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressionAnchorTest {

    @Test
    fun emptyScanHasNoAnchor() {
        assertEquals(ProgressionAnchor(-1, false), progressionAnchor(emptyList(), 0.5))
    }

    @Test
    fun anchorsAtLastElementAtOrBeforeTarget() {
        val anchor = progressionAnchor(listOf(0.0, 0.2, 0.4, 0.6), target = 0.5)

        assertEquals(2, anchor.index)
        // The element past the target (0.6) was consumed while scanning and must be re-read.
        assertTrue(anchor.stepBack)
    }

    @Test
    fun anchorsAtFirstElementWhenSelectorAlreadyResolvedPastTarget() {
        val anchor = progressionAnchor(listOf(0.7, 0.8), target = 0.5)

        assertEquals(0, anchor.index)
        assertFalse(anchor.stepBack)
    }

    @Test
    fun anchorsAtLastElementWhenTargetIsPastTheResource() {
        val anchor = progressionAnchor(listOf(0.0, 0.3, 0.6), target = 0.9)

        assertEquals(2, anchor.index)
        assertFalse(anchor.stepBack)
    }

    @Test
    fun crossingIntoTheNextResourceReReadsItsFirstElement() {
        val anchor = progressionAnchor(listOf(0.0, 0.3), target = 0.9, crossedIntoNextResource = true)

        assertEquals(1, anchor.index)
        assertTrue(anchor.stepBack)
    }

    @Test
    fun nullProgressionsAreTreatedAsAtOrBeforeTarget() {
        val anchor = progressionAnchor(listOf(null, null, 0.8), target = 0.5)

        assertEquals(1, anchor.index)
        assertTrue(anchor.stepBack)
    }
}
