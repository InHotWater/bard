package com.stan.libbylight.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSessionMappingsTest {
    @Test fun seeksAreClamped() {
        assertEquals(0L, clampMediaSeek(-1L, 60_000L))
        assertEquals(60_000L, clampMediaSeek(80_000L, 60_000L))
    }

    @Test fun audiobookIntervalsAreStable() {
        assertEquals(40_000L, mediaFastForwardTarget(10_000L, 100_000L))
        assertEquals(0L, mediaRewindTarget(10_000L, 100_000L))
        assertEquals(20_000L, mediaRewindTarget(50_000L, 100_000L))
        assertEquals(100_000L, mediaFastForwardTarget(90_000L, 100_000L))
    }

    @Test fun previousRewindsInsteadOfRestartingTheBook() {
        assertEquals(3_570_000L, mediaPreviousTarget(3_600_000L, 7_200_000L))
        assertEquals(0L, mediaPreviousTarget(10_000L, 100_000L))
    }

    @Test fun metadataContainsOnlyDisplayValues() {
        assertEquals(SafeMediaMetadata("A Book", "An Author"), safeMediaMetadata("A Book", "An Author"))
        assertEquals(SafeMediaMetadata("Bard", null), safeMediaMetadata("", ""))
    }

    @Test fun duplicateCommandsAreSuppressedInsideWindow() {
        var now = 1_000L
        val deduplicator = MediaCommandDeduplicator({ now }, 250L)
        assertTrue(deduplicator.shouldDispatch("play"))
        now += 100
        assertFalse(deduplicator.shouldDispatch("play"))
        now += 300
        assertTrue(deduplicator.shouldDispatch("play"))
    }
}
