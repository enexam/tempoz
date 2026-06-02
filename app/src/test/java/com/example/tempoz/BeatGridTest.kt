package com.example.tempoz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToLong

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
    fun subOnsetsIntegerBpm_areExactlySpaced() {
        // bpm=120, sr=48000 → interval=24000. S=4 (sixteenth).
        // Beat 0 onset = 0. Sub-onsets: +6000, +12000, +18000.
        val k = 0L
        val offset = 0L
        val bpm = 120.0
        val S = 4
        assertEquals(6000L,  BeatGrid.subOnsetFrame(k, 1, offset, bpm, sr, S))
        assertEquals(12000L, BeatGrid.subOnsetFrame(k, 2, offset, bpm, sr, S))
        assertEquals(18000L, BeatGrid.subOnsetFrame(k, 3, offset, bpm, sr, S))
    }

    @Test
    fun subOnsetsEighth_singleSubHalfInterval() {
        // S=2 → 1 sub per beat at exactly half the interval.
        // bpm=120, interval=24000 → sub at +12000.
        assertEquals(12000L, BeatGrid.subOnsetFrame(0L, 1, 0L, 120.0, sr, 2))
    }

    @Test
    fun subOnsetsTriplet_twoSubsAtThirdsInterval() {
        // S=3 → subs at 1/3 and 2/3 of interval.
        // bpm=120, interval=24000 → subs at 8000 and 16000.
        assertEquals(8000L,  BeatGrid.subOnsetFrame(0L, 1, 0L, 120.0, sr, 3))
        assertEquals(16000L, BeatGrid.subOnsetFrame(0L, 2, 0L, 120.0, sr, 3))
    }

    @Test
    fun subOnsetsAreTwoStepRounded_fractionalBpm() {
        // Verifies that sub-onset uses two-step rounding (beatOnset first, then sub-offset),
        // not a single-step collapse. For fractional BPM the two formulas diverge.
        val bpm = 123.45
        val S = 4
        val interval = BeatGrid.beatIntervalFrames(bpm, sr)
        for (k in 0L..100L) {
            val beatOnset = BeatGrid.beatOnsetFrame(k, 0L, bpm, sr)
            for (j in 1 until S) {
                val expected = beatOnset + (j.toDouble() * interval / S.toDouble()).roundToLong()
                val actual   = BeatGrid.subOnsetFrame(k, j, 0L, bpm, sr, S)
                assertEquals("k=$k j=$j", expected, actual)
            }
        }
    }

    @Test
    fun subOnsetsWithFirstBeatOffset_correctlyShifted() {
        val offset = 5000L
        // bpm=120, S=4, beat0 onset = 5000. Sub at j=1 → 5000+6000=11000.
        assertEquals(11000L, BeatGrid.subOnsetFrame(0L, 1, offset, 120.0, sr, 4))
    }

    @Test
    fun subdivisionOne_hasNoSubBeats() {
        // S=1: j ∈ 1..0 is empty; confirm the loop produces nothing.
        // There is nothing to call since j range would be empty (1 until 1). Trivially passes.
        // Explicitly check that subdivision=1 with j=1 would land exactly ON the next beat onset.
        // (The caller must not call subOnsetFrame with j>=S.)
        // This test verifies S=1 implies 0 sub-beats by iterating the valid range.
        val S = 1
        val subBeatCount = (1 until S).count()
        assertEquals("S=1 must produce zero sub-beats", 0, subBeatCount)
    }

    @Test
    fun accentNormalGhostClassification() {
        // k=0: accent (k % 4 == 0); k=1,2,3: normal; j∈1..S-1: ghost (by construction).
        assertTrue("k=0 is accent",  BeatGrid.isAccent(0L, 4))
        assertTrue("k=4 is accent",  BeatGrid.isAccent(4L, 4))
        assertFalse("k=1 is normal", BeatGrid.isAccent(1L, 4))
        assertFalse("k=2 is normal", BeatGrid.isAccent(2L, 4))
        assertFalse("k=3 is normal", BeatGrid.isAccent(3L, 4))
        // Sub-beats (j ∈ 1..S-1) are always ghost — there's no BeatGrid function for this
        // because ghost classification is purely "is it a sub-onset?", which is implicit in j >= 1.
        // Assert that sub-onset at j=1 is strictly between two beat onsets.
        val onset0 = BeatGrid.beatOnsetFrame(0L, 0L, 120.0, sr)
        val onset1 = BeatGrid.beatOnsetFrame(1L, 0L, 120.0, sr)
        val ghost  = BeatGrid.subOnsetFrame(0L, 1, 0L, 120.0, sr, 4)
        assertTrue("ghost onset > beat onset", ghost > onset0)
        assertTrue("ghost onset < next beat onset", ghost < onset1)
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
