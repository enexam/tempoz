package com.example.tempoz

import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Canonical metronome beat-grid math — the single source of truth for where
 * click beats fall. The native [ClickGenerator] (C++) implements the identical
 * formulas; keep the two in lockstep.
 *
 * Everything here is a pure function of an absolute frame position, with no
 * carried state. That is the property that makes the click deterministic: after
 * any seek/pause/resume the grid is recomputed from the playhead, so beats can
 * neither drift nor desync. Because it is pure and dependency-free it is the one
 * sync-critical piece that can be unit-tested on the JVM without a device.
 *
 * A "beat index" k is 0-based and counted from [firstBeatOffset]. Beat 0 of each
 * bar (k % beatsPerBar == 0) is the accented downbeat.
 */
object BeatGrid {

    /** Frames per beat at [sampleRate] for [bpm] (fractional, no rounding). */
    fun beatIntervalFrames(bpm: Double, sampleRate: Int): Double =
        sampleRate.toDouble() * 60.0 / bpm

    /**
     * Index of the beat at or before [frame], or -1 if [frame] precedes the
     * first beat. Flooring the fractional beat position makes this a pure
     * function of position that realigns instantly after a seek.
     */
    fun beatIndexAt(frame: Long, firstBeatOffset: Long, bpm: Double, sampleRate: Int): Long {
        if (frame < firstBeatOffset) return -1L
        val interval = beatIntervalFrames(bpm, sampleRate)
        return floor((frame - firstBeatOffset).toDouble() / interval).toLong()
    }

    /**
     * Absolute onset frame of beat [k]. Computed as round(k * interval) each
     * call rather than by accumulation, so beat spacing never drifts for
     * non-integer BPM. Mirrors std::llround in the C++ generator (identical for
     * the non-negative values used here).
     */
    fun beatOnsetFrame(k: Long, firstBeatOffset: Long, bpm: Double, sampleRate: Int): Long {
        val interval = beatIntervalFrames(bpm, sampleRate)
        return firstBeatOffset + (k.toDouble() * interval).roundToLong()
    }

    /** True if beat [k] is the accented downbeat of its bar. */
    fun isAccent(k: Long, beatsPerBar: Int): Boolean = (k % beatsPerBar) == 0L

    /**
     * Absolute onset frame of sub-beat [j] within beat [k], where [j] ∈ 1..[subdivision]-1.
     *
     * Formula: beatOnset(k) + round(j * interval / subdivision).
     *
     * This is a **two-step** rounding: beat onset is already rounded via [beatOnsetFrame],
     * and the sub-beat offset is rounded independently. Do NOT collapse to
     * round((k + j/S) * interval) — that produces different values for fractional BPM.
     *
     * Mirrors the C++ ClickGenerator sub-onset math (keep in lockstep).
     *
     * @param k             beat index (0-based from [firstBeatOffset])
     * @param j             sub-beat index within the beat, 1 ≤ j < [subdivision]
     * @param subdivision   number of subdivisions per beat (1=quarter/none, 2=eighth, 3=triplet,
     *                      4=sixteenth)
     */
    fun subOnsetFrame(k: Long, j: Int, firstBeatOffset: Long, bpm: Double, sampleRate: Int,
                      subdivision: Int): Long {
        val interval = beatIntervalFrames(bpm, sampleRate)
        val beatOnset = beatOnsetFrame(k, firstBeatOffset, bpm, sampleRate)
        return beatOnset + (j.toDouble() * interval / subdivision.toDouble()).roundToLong()
    }
}
