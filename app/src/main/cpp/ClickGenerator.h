#pragma once

#include <cstdint>

/**
 * Synthesizes a metronome click track as float32 PCM samples.
 *
 * Beat 1 of each bar uses 880 Hz; all other beats use 660 Hz.
 * Each click has an exponential decay envelope: gain * exp(-decay * t).
 */
class ClickGenerator {
public:
    ClickGenerator() = default;

    /**
     * (Re)configure the generator. Safe to call before the first render.
     *
     * @param bpm                beats per minute
     * @param beatsPerBar        number of beats in one bar (accent on beat 0)
     * @param sampleRate         output sample rate in Hz
     * @param initialOffsetFrames frames to wait before the first beat fires;
     *                           0 fires a beat on the very first rendered frame.
     *                           Negative values are clamped to 0.
     */
    void configure(int bpm, int beatsPerBar, int sampleRate, int64_t initialOffsetFrames = 0);

    /**
     * Fill [out] in-place with click samples mixed at [gain].
     *
     * The output is interleaved; [numFrames] * [channels] floats are written.
     * Existing content in [out] is summed with the click — caller must zero it
     * beforehand if a fresh buffer is needed.
     *
     * @param out       output buffer (interleaved float32), numFrames * channels elements
     * @param numFrames number of audio frames to render
     * @param channels  number of interleaved channels (e.g. 2 for stereo)
     * @param gain      master gain applied to each click sample
     */
    void render(float* out, int numFrames, int channels, float gain);

private:
    // Frames remaining until the next beat fires (0 = fire on next frame).
    int64_t mFramesUntilNextBeat = 0;
    // 0-based index of the current beat within a bar.
    int mCurrentBeat = 0;
    // Frames of click envelope remaining for the ongoing click (0 = silent).
    int mClickFramesRemaining = 0;
    // Click duration in frames (computed from sample rate).
    int mClickDurationFrames = 0;
    // Current oscillator phase in radians.
    float mPhase = 0.0f;
    // Frequency of the current click (880 Hz accent or 660 Hz normal).
    float mFreq = 0.0f;

    int mBpm = 120;
    int mBeatsPerBar = 4;
    int mSampleRate = 48000;
    int64_t mBeatIntervalFrames = 24000; // sampleRate * 60 / bpm

    static constexpr float kDecay = 80.0f;
    static constexpr float kAccentFreq = 880.0f;
    static constexpr float kNormalFreq = 660.0f;
    // Click duration: 30 ms expressed as fraction of sample rate.
    static constexpr float kClickDurationSeconds = 0.030f;
};
