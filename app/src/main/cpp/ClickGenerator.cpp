#include "ClickGenerator.h"

#include <cmath>
#include <cstddef>

void ClickGenerator::render(float* out, int numFrames, int channels,
                            int64_t startFrame, double bpm, int beatsPerBar,
                            int64_t firstBeatOffset, int sampleRate, float gain) const {
    if (bpm <= 0.0 || beatsPerBar <= 0 || sampleRate <= 0) return;

    const double interval = static_cast<double>(sampleRate) * 60.0 / bpm;  // frames per beat
    const int clickDur = static_cast<int>(kClickDurationSeconds * sampleRate);
    const float twoPi = 2.0f * static_cast<float>(M_PI);
    const float sr = static_cast<float>(sampleRate);

    for (int i = 0; i < numFrames; ++i) {
        const int64_t abs = startFrame + i;
        if (abs < firstBeatOffset) continue;  // before the first beat: silence

        // Index of the beat at or before this frame, and that beat's onset.
        // Both are derived purely from the absolute position (see BeatGrid.kt),
        // so onsets never accumulate rounding error and seeking cannot shift them.
        const double rel = static_cast<double>(abs - firstBeatOffset);
        const int64_t k = static_cast<int64_t>(std::floor(rel / interval));
        const int64_t onset = firstBeatOffset + std::llround(static_cast<double>(k) * interval);

        const int64_t elapsed = abs - onset;
        if (elapsed < 0 || elapsed >= clickDur) continue;  // between clicks

        const float freq = ((k % beatsPerBar) == 0) ? kAccentFreq : kNormalFreq;
        const float t = static_cast<float>(elapsed) / sr;
        const float env = gain * std::exp(-kDecay * t);
        // Phase derived from elapsed frames keeps the burst continuous without
        // any oscillator state carried across blocks.
        const float phase = twoPi * freq * static_cast<float>(elapsed) / sr;
        const float sample = env * std::sin(phase);

        float* frameOut = out + static_cast<std::ptrdiff_t>(i) * channels;
        for (int c = 0; c < channels; ++c) frameOut[c] += sample;
    }
}
