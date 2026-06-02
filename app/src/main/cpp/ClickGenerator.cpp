#include "ClickGenerator.h"

#include <cmath>
#include <cstddef>
#include <cstdint>

// ---- Deterministic noise for Hi-hat ----
// Maps an absolute frame index to a pseudo-random float in [-1, 1].
// Uses a 32-bit Knuth multiplicative hash. Same frame always produces the same
// value, so the hi-hat is seek-stable and cannot drift.
static inline float hashNoise(int64_t frame) {
    uint32_t h = static_cast<uint32_t>(frame) * 2654435761u;
    h ^= h >> 15;
    h *= 2246822519u;
    h ^= h >> 13;
    // Map [0, UINT32_MAX] → [-1, 1]
    return static_cast<float>(static_cast<int32_t>(h)) / 2147483648.0f;
}

void ClickGenerator::render(float* out, int numFrames, int channels,
                            int64_t startFrame, double bpm, int beatsPerBar,
                            int64_t firstBeatOffset, int sampleRate, float gain,
                            int clickSound) const {
    if (bpm <= 0.0 || beatsPerBar <= 0 || sampleRate <= 0) return;

    // Clamp to valid voice range.
    if (clickSound < 0 || clickSound > 5) clickSound = 0;

    const double interval = static_cast<double>(sampleRate) * 60.0 / bpm;
    const float twoPi = 2.0f * static_cast<float>(M_PI);
    const float sr = static_cast<float>(sampleRate);

    // Per-voice parameters. Computed once per block (pure of abs position).
    // accentFreq / normalFreq in Hz; clickDurFrames; decay rate; voice scale.
    float accentFreq, normalFreq;
    int   clickDurFrames;
    float decay;
    float voiceScale;  // pre-gain multiplier to keep loud voices below clip

    switch (clickSound) {
        case 1:  // Rim: short bright sine burst
            accentFreq    = 1900.0f;
            normalFreq    = 1500.0f;
            clickDurFrames = static_cast<int>(0.015f * sr);
            decay          = 150.0f;
            voiceScale     = 1.0f;
            break;
        case 2:  // Wood block: multi-harmonic (fundamental + 3rd harmonic)
            accentFreq    = 1100.0f;
            normalFreq    = 850.0f;
            clickDurFrames = static_cast<int>(0.025f * sr);
            decay          = 110.0f;
            voiceScale     = 0.45f;  // sum of harmonics can clip without this
            break;
        case 3:  // Beep: clean sustained sine
            accentFreq    = 1200.0f;
            normalFreq    = 1000.0f;
            clickDurFrames = static_cast<int>(0.100f * sr);
            decay          = 18.0f;
            voiceScale     = 1.0f;
            break;
        case 4:  // Cowbell: two detuned sines
            accentFreq    = 800.0f;   // primary pitch (secondary is fixed below)
            normalFreq    = 640.0f;
            clickDurFrames = static_cast<int>(0.060f * sr);
            decay          = 40.0f;
            voiceScale     = 0.5f;   // sum of two sines
            break;
        case 5:  // Hi-hat: deterministic noise burst
            accentFreq    = 0.0f;    // unused
            normalFreq    = 0.0f;
            clickDurFrames = static_cast<int>(0.035f * sr);
            decay          = 120.0f;
            voiceScale     = 0.5f;
            break;
        default: // Click (0): 30 ms sine burst
            accentFreq    = 880.0f;
            normalFreq    = 660.0f;
            clickDurFrames = static_cast<int>(0.030f * sr);
            decay          = 80.0f;
            voiceScale     = 1.0f;
            break;
    }

    for (int i = 0; i < numFrames; ++i) {
        const int64_t abs = startFrame + i;
        if (abs < firstBeatOffset) continue;

        // Beat index k and its onset — derived purely from the absolute position,
        // so onsets never accumulate rounding error and seeking cannot shift them.
        const double rel = static_cast<double>(abs - firstBeatOffset);
        const int64_t k = static_cast<int64_t>(std::floor(rel / interval));
        const int64_t onset = firstBeatOffset + std::llround(static_cast<double>(k) * interval);

        const int64_t elapsed = abs - onset;
        if (elapsed < 0 || elapsed >= clickDurFrames) continue;

        const bool accent = (k % beatsPerBar) == 0;

        float sample;
        const float t = static_cast<float>(elapsed) / sr;
        const float env = gain * voiceScale * std::exp(-decay * t);

        switch (clickSound) {
            case 1:  // Rim
            case 3:  // Beep
            case 0:  // Click
            default: {
                const float freq = accent ? accentFreq : normalFreq;
                const float phase = twoPi * freq * static_cast<float>(elapsed) / sr;
                sample = env * std::sin(phase);
                break;
            }
            case 2: {  // Wood block: fundamental + 3rd harmonic, slight square-ish texture
                const float freq = accent ? accentFreq : normalFreq;
                const float phBase = twoPi * freq * static_cast<float>(elapsed) / sr;
                sample = env * (std::sin(phBase) + 0.35f * std::sin(3.0f * phBase));
                break;
            }
            case 4: {  // Cowbell: two detuned sines
                const float f1 = accent ? accentFreq : normalFreq;
                const float f2 = accent ? 540.0f : 432.0f;  // fixed lower partial
                const float ph1 = twoPi * f1 * static_cast<float>(elapsed) / sr;
                const float ph2 = twoPi * f2 * static_cast<float>(elapsed) / sr;
                sample = env * (std::sin(ph1) + std::sin(ph2));
                break;
            }
            case 5: {  // Hi-hat: deterministic noise burst
                // Accent = louder and slightly longer decay (handled by higher gain).
                const float accentBoost = accent ? 1.6f : 1.0f;
                sample = env * accentBoost * hashNoise(abs);
                break;
            }
        }

        float* frameOut = out + static_cast<std::ptrdiff_t>(i) * channels;
        for (int c = 0; c < channels; ++c) frameOut[c] += sample;
    }
}
