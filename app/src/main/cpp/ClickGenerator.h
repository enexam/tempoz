#pragma once

#include <cstdint>

/**
 * Synthesizes a metronome click track as a pure function of absolute playback
 * position.
 *
 * There is no carried beat state: the output for any frame depends only on the
 * playhead frame passed to render(). This is what makes the click impossible to
 * desync — after a seek, pause, or resume the click realigns to the recording's
 * beat grid automatically, because it is recomputed from the playhead every
 * block.
 *
 * Beat k (0-based, counted from firstBeatOffset) has onset frame
 *   onset(k) = firstBeatOffset + llround(k * beatIntervalFrames)
 * Beat 0 of each bar (k % beatsPerBar == 0) uses the accent frequency/gain;
 * all others use the normal frequency/gain. Each click duration and shape
 * depends on the selected voice (clickSound).
 *
 * The beat-grid math mirrors BeatGrid.kt, which carries the JVM unit tests.
 * Keep the two in lockstep.
 *
 * Click sound ids (must match ClickSoundOptions order in PlaybackViewModel.kt):
 *   0 = Click     — sine burst, 880/660 Hz, 30 ms
 *   1 = Rim       — bright sine, 1900/1500 Hz, 15 ms
 *   2 = Wood block — multi-harmonic, 1100/850 Hz, 25 ms
 *   3 = Beep      — clean sine, 1200/1000 Hz, 100 ms
 *   4 = Cowbell   — dual detuned sines, 800+540 Hz, 60 ms
 *   5 = Hi-hat    — deterministic noise burst, 35 ms
 */
class ClickGenerator {
public:
    ClickGenerator() = default;

    /**
     * Sum click samples for the output block spanning absolute frames
     * [startFrame, startFrame + numFrames) into [out] (interleaved).
     *
     * Existing content in [out] is summed with the click — the caller must zero
     * the buffer first if a fresh signal is needed.
     *
     * @param out             interleaved float32, numFrames * channels elements
     * @param numFrames       number of frames to render
     * @param channels        interleaved channel count (e.g. 2 for stereo)
     * @param startFrame      absolute frame index of out[0] (the playhead)
     * @param bpm             beats per minute (must be > 0)
     * @param beatsPerBar     beats per bar, accent on beat 0 (must be > 0)
     * @param firstBeatOffset absolute frame of the first beat (beat 0)
     * @param sampleRate      output sample rate in Hz (must be > 0)
     * @param gain            master gain applied to each click sample
     * @param clickSound      voice selector (0–5, see class doc; clamped to valid range)
     */
    void render(float* out, int numFrames, int channels,
                int64_t startFrame, double bpm, int beatsPerBar,
                int64_t firstBeatOffset, int sampleRate, float gain,
                int clickSound) const;
};
