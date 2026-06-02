package com.example.tempoz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [phaseLockOffsetFrames], the pure helper that computes the
 * first-beat offset so a beat lands on the tap position.
 *
 * The formula under test:
 *   outputFrame = audibleMs / 1000.0 * 48000.0
 *   sourceFrame = round(outputFrame * speed)
 *   interval    = BeatGrid.beatIntervalFrames(bpm, 48000)
 *   offset      = ((sourceFrame % interval) + interval) % interval
 * Result is a non-negative Long in source frames (48 kHz, speed-adjusted).
 */
class TapTempoPhaseTest {

    private val sr = 48000

    /** Interval in frames for 120.0 BPM at 48000 Hz = 24000.0 frames/beat. */
    private fun interval120() = BeatGrid.beatIntervalFrames(120.0, sr)

    @Test
    fun tapOnBeatOnset_returnsZeroOffset() {
        // Position exactly at beat 2 onset (frame=48000) at speed=1.0, bpm=120.
        // sourceFrame = 48000. 48000 % 24000.0 = 0.0 → offset = 0.
        val audibleMs = (48000L * 1000L / sr)  // 1000 ms
        val result = phaseLockOffsetFrames(audibleMs, 1.0f, 120.0)
        assertNotNull(result)
        assertEquals(0L, result)
    }

    @Test
    fun tapMidBeat_returnsHalfInterval() {
        // Position at 12000 frames (halfway through beat 0) at speed=1.0, bpm=120.
        // sourceFrame=12000. 12000 % 24000.0 = 12000.0 → offset = 12000.
        val audibleMs = (12000L * 1000L / sr)  // 250 ms
        val result = phaseLockOffsetFrames(audibleMs, 1.0f, 120.0)
        assertNotNull(result)
        assertEquals(12000L, result!!)
    }

    @Test
    fun tapAtQuarterBeat_returnsQuarterInterval() {
        // Position at 6000 frames (quarter-beat) at speed=1.0, bpm=120.
        // sourceFrame=6000. 6000 % 24000.0 = 6000.0 → offset = 6000.
        val audibleMs = (6000L * 1000L / sr)  // 125 ms
        val result = phaseLockOffsetFrames(audibleMs, 1.0f, 120.0)
        assertNotNull(result)
        assertEquals(6000L, result!!)
    }

    @Test
    fun speedAboveOne_scalesSourceFrame() {
        // audibleMs = 250 ms → outputFrame = 12000. speed=2.0 → sourceFrame=24000.
        // 24000 % 24000.0 = 0.0 → offset = 0.
        val audibleMs = 250L  // 12000 output frames at 48kHz
        val result = phaseLockOffsetFrames(audibleMs, 2.0f, 120.0)
        assertNotNull(result)
        assertEquals(0L, result!!)
    }

    @Test
    fun speedBelowOne_scalesSourceFrame() {
        // audibleMs = 500 ms → outputFrame = 24000. speed=0.5 → sourceFrame=12000.
        // 12000 % 24000.0 = 12000.0 → offset = 12000.
        val audibleMs = 500L  // 24000 output frames at 48kHz
        val result = phaseLockOffsetFrames(audibleMs, 0.5f, 120.0)
        assertNotNull(result)
        assertEquals(12000L, result!!)
    }

    @Test
    fun offsetIsAlwaysNonNegative() {
        // The double-modulo ((x%n)+n)%n ensures a non-negative result for any position.
        // Use a large arbitrary position to ensure no special-case aliasing.
        val result = phaseLockOffsetFrames(7_654_321L, 1.0f, 137.5)
        assertNotNull(result)
        assertTrue("offset must be >= 0", result!! >= 0L)
    }

    @Test
    fun offsetIsLessThanOneInterval() {
        // The offset must be in [0, interval), i.e., < one beat interval.
        val bpm = 98.5
        val result = phaseLockOffsetFrames(999_999L, 1.0f, bpm)
        assertNotNull(result)
        val interval = BeatGrid.beatIntervalFrames(bpm, sr)
        assertTrue(
            "offset ${result!!} must be < interval ${interval.toLong()}",
            result < interval.toLong() + 1  // allow ±1 frame for rounding
        )
    }

    @Test
    fun zeroBpm_returnsNull() {
        // bpm=0 produces interval=Infinity or div-by-zero; the helper must return null.
        val result = phaseLockOffsetFrames(1000L, 1.0f, 0.0)
        assertNull(result)
    }

    @Test
    fun negativeBpm_returnsNull() {
        val result = phaseLockOffsetFrames(1000L, 1.0f, -10.0)
        assertNull(result)
    }

    @Test
    fun fractionalBpm_producesCorrectOffset() {
        // bpm=98.5, sr=48000. interval = 48000*60/98.5 ≈ 29238.578...
        // Construct audibleMs such that we get exactly halfInterval source frames.
        // Note: ms→frame conversion truncates sub-ms precision, so we verify
        // the offset is in [0, interval) and approximately at half-interval.
        val bpm = 98.5
        val interval = BeatGrid.beatIntervalFrames(bpm, sr)
        val halfInterval = (interval / 2).toLong()
        // audibleMs that maps to approximately halfInterval output frames.
        // Integer ms → frame round-trip can differ by up to 48 frames (1 ms at 48kHz).
        val audibleMs = halfInterval * 1000L / sr
        val result = phaseLockOffsetFrames(audibleMs, 1.0f, bpm)
        assertNotNull(result)
        // Must be in [0, interval) — that's the core invariant.
        assertTrue("offset ${result!!} must be >= 0", result >= 0L)
        assertTrue("offset $result must be < interval", result < interval.toLong() + 1)
        // Must be in the ballpark of halfInterval — within 50 frames (1 ms rounding).
        assertTrue(
            "offset $result should be near $halfInterval (within 50 frames)",
            kotlin.math.abs(result - halfInterval) <= 50L
        )
    }

    @Test
    fun msToFrameConversionUsesDoublePrecision() {
        // Regression: if audibleMs/1000 * 48000 is done in Long arithmetic,
        // 1500ms → 1*48000=48000 instead of 1.5*48000=72000.
        // At 120 BPM: 48000 % 24000 = 0, but 72000 % 24000 = 0 too (both multiples).
        // Use 750ms: integer truncation → 0*48000=0 (offset=0), correct → 36000 (offset=12000).
        val result = phaseLockOffsetFrames(750L, 1.0f, 120.0)
        assertNotNull(result)
        // 750ms = 0.75s → outputFrame = 0.75 * 48000 = 36000. 36000 % 24000 = 12000.
        assertEquals(12000L, result!!)
    }
}
