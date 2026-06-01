package com.example.tempoz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Tests the [BeatGrid] math — the metronome's sync-critical core, mirrored by
 * the native ClickGenerator. Covers the failure modes that show up as "the click
 * drifts" or "the click is out of sync after seeking": accumulated drift,
 * fractional-BPM rounding, accent placement, the pre-first-beat offset, and
 * grid alignment at arbitrary seek targets.
 */
class BeatGridTest {

    private val sr = 48000

    @Test
    fun integerBpm_hasExactSpacingAndNoDrift() {
        val interval = BeatGrid.beatIntervalFrames(120.0, sr)
        assertEquals(24000.0, interval, 0.0)
        assertEquals(0L, BeatGrid.beatOnsetFrame(0L, 0L, 120.0, sr))
        var prev = 0L
        for (k in 1..2000L) {
            val onset = BeatGrid.beatOnsetFrame(k, 0L, 120.0, sr)
            assertEquals("spacing at beat $k", 24000L, onset - prev)
            prev = onset
        }
    }

    @Test
    fun fractionalBpm_doesNotAccumulateDrift() {
        val bpm = 123.45
        val interval = BeatGrid.beatIntervalFrames(bpm, sr)
        // Every onset stays within one frame of the ideal continuous position,
        // even after thousands of beats — proves error does not accumulate.
        for (k in 0..10_000L) {
            val onset = BeatGrid.beatOnsetFrame(k, 0L, bpm, sr)
            val ideal = k.toDouble() * interval
            assertTrue(
                "beat $k deviates ${abs(onset - ideal)} frames",
                abs(onset.toDouble() - ideal) <= 1.0
            )
        }
    }

    @Test
    fun accentFallsOnEveryDownbeat() {
        for (k in longArrayOf(0L, 4L, 8L, 400L)) {
            assertTrue("beat $k should accent", BeatGrid.isAccent(k, 4))
        }
        for (k in longArrayOf(1L, 2L, 3L, 5L, 6L, 7L)) {
            assertFalse("beat $k should not accent", BeatGrid.isAccent(k, 4))
        }
        // Odd meter still accents only the bar start.
        assertTrue(BeatGrid.isAccent(0L, 7))
        assertTrue(BeatGrid.isAccent(7L, 7))
        assertFalse(BeatGrid.isAccent(6L, 7))
    }

    @Test
    fun framesBeforeFirstBeatHaveNoBeat() {
        val offset = 5000L
        assertEquals(-1L, BeatGrid.beatIndexAt(offset - 1, offset, 120.0, sr))
        assertEquals(0L, BeatGrid.beatIndexAt(offset, offset, 120.0, sr))
        assertEquals(0L, BeatGrid.beatIndexAt(offset + 23_999, offset, 120.0, sr))
        assertEquals(1L, BeatGrid.beatIndexAt(offset + 24_000, offset, 120.0, sr))
    }

    @Test
    fun seekTargetsAlwaysLandOnTheGrid() {
        // beatIndexAt/beatOnsetFrame are pure functions of position, so jumping
        // to an arbitrary frame (a seek) still maps to the onset at or before it,
        // within one interval. This is the "resync after seek" guarantee.
        val offset = 1234L
        val bpm = 137.0
        val interval = BeatGrid.beatIntervalFrames(bpm, sr)
        for (frame in longArrayOf(offset, 50_000L, 1_000_000L, 7_777_777L)) {
            val k = BeatGrid.beatIndexAt(frame, offset, bpm, sr)
            assertTrue("beat index >= 0 at $frame", k >= 0L)
            val onset = BeatGrid.beatOnsetFrame(k, offset, bpm, sr)
            assertTrue("onset $onset must be <= frame $frame", onset <= frame)
            assertTrue("frame $frame must be before next onset", frame - onset < interval + 1.0)
        }
    }
}
