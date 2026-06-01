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
 * Beat 0 of each bar (k % beatsPerBar == 0) uses 880 Hz; all others 660 Hz.
 * Each click is a 30 ms sine burst with an exponential decay envelope.
 *
 * The beat-grid math mirrors BeatGrid.kt, which carries the JVM unit tests.
 * Keep the two in lockstep.
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
     */
    void render(float* out, int numFrames, int channels,
                int64_t startFrame, double bpm, int beatsPerBar,
                int64_t firstBeatOffset, int sampleRate, float gain) const;

private:
    static constexpr float kDecay = 80.0f;                  // envelope decay rate
    static constexpr float kAccentFreq = 880.0f;            // beat 0 of each bar
    static constexpr float kNormalFreq = 660.0f;            // every other beat
    static constexpr float kClickDurationSeconds = 0.030f;  // 30 ms burst
};
