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

// Synthesize a single click sample for the given voice.
// elapsed    : frames since the click onset
// isAccent   : true for the downbeat voice (accent freq/gain), false for normal
// isGhost    : true for sub-beat ghost clicks (uses normalFreq, shorter decay, ghostEnv)
// freq1/freq2: accent and normal fundamental frequency for this voice
// The caller passes pre-computed env (main or ghost) and the per-voice logic.
// Returns the sample to ADD into the output buffer.
static float singleClickSample(int clickSound,
                                int64_t elapsed, float tSec,
                                bool accentBeat, float env,
                                float accentFreq, float normalFreq,
                                float twoPi, float sr,
                                int64_t absFrame) {
    const float freq = accentBeat ? accentFreq : normalFreq;
    switch (clickSound) {
        case 1:   // Rim
        case 3:   // Beep
        case 0:   // Click
        default: {
            const float phase = twoPi * freq * static_cast<float>(elapsed) / sr;
            return env * std::sin(phase);
        }
        case 2: { // Wood block: fundamental + 3rd harmonic
            const float phBase = twoPi * freq * static_cast<float>(elapsed) / sr;
            return env * (std::sin(phBase) + 0.35f * std::sin(3.0f * phBase));
        }
        case 4: { // Cowbell: two detuned sines
            const float f2 = accentBeat ? 540.0f : 432.0f;
            const float ph1 = twoPi * freq  * static_cast<float>(elapsed) / sr;
            const float ph2 = twoPi * f2    * static_cast<float>(elapsed) / sr;
            return env * (std::sin(ph1) + std::sin(ph2));
        }
        case 5: { // Hi-hat: deterministic noise burst
            const float accentBoost = accentBeat ? 1.6f : 1.0f;
            return env * accentBoost * hashNoise(absFrame);
        }
    }
}

void ClickGenerator::render(float* out, int numFrames, int channels,
                            int64_t startFrame, double bpm, int beatsPerBar,
                            int64_t firstBeatOffset, int sampleRate, float gain,
                            int clickSound, int subdivision, float ghostVolume) const {
    if (bpm <= 0.0 || beatsPerBar <= 0 || sampleRate <= 0) return;

    // Clamp to valid voice range.
    if (clickSound < 0 || clickSound > 5) clickSound = 0;
    // Clamp subdivision to [1,4].
    if (subdivision < 1 || subdivision > 4) subdivision = 1;

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

    // Ghost click duration: half of the main-beat duration (shorter, less prominent).
    const int ghostDurFrames = clickDurFrames / 2;

    for (int i = 0; i < numFrames; ++i) {
        const int64_t absFrame = startFrame + i;
        if (absFrame < firstBeatOffset) continue;

        // Beat index k and its onset — derived purely from the absolute position,
        // so onsets never accumulate rounding error and seeking cannot shift them.
        // Identical formula to BeatGrid.kt beatOnsetFrame / beatIndexAt.
        const double rel = static_cast<double>(absFrame - firstBeatOffset);
        const int64_t k = static_cast<int64_t>(std::floor(rel / interval));
        const int64_t onset = firstBeatOffset + std::llround(static_cast<double>(k) * interval);

        float sample = 0.0f;

        // ---- Main beat ----
        {
            const int64_t elapsed = absFrame - onset;
            if (elapsed >= 0 && elapsed < clickDurFrames) {
                const bool accent = (k % beatsPerBar) == 0;
                const float t = static_cast<float>(elapsed) / sr;
                const float env = gain * voiceScale * std::exp(-decay * t);
                sample += singleClickSample(clickSound, elapsed, t, accent, env,
                                            accentFreq, normalFreq, twoPi, sr, absFrame);
            }
        }

        // ---- Ghost sub-beats (S-1 per beat) ----
        // Sub-beat j onset = beatOnset(k) + llround(j * interval / S).
        // Mirrors BeatGrid.kt subOnsetFrame (two-step rounding, keep in lockstep).
        if (subdivision > 1) {
            // We may also fall inside the ghost window of a sub-beat belonging to beat k-1.
            // Check beat k and k-1 to cover the case where absFrame is close to the boundary.
            for (int dk = 0; dk <= 1; ++dk) {
                const int64_t beatK = k - static_cast<int64_t>(dk);
                if (beatK < 0) continue;
                const int64_t beatOnset = firstBeatOffset
                    + std::llround(static_cast<double>(beatK) * interval);
                for (int j = 1; j < subdivision; ++j) {
                    const int64_t subOnset = beatOnset
                        + std::llround(static_cast<double>(j) * interval
                                       / static_cast<double>(subdivision));
                    const int64_t elapsed = absFrame - subOnset;
                    if (elapsed >= 0 && elapsed < ghostDurFrames) {
                        const float t = static_cast<float>(elapsed) / sr;
                        // Ghost always uses normalFreq (no accent), scaled by ghostVolume.
                        const float env = gain * ghostVolume * voiceScale * std::exp(-decay * t);
                        sample += singleClickSample(clickSound, elapsed, t, /*accent=*/false, env,
                                                    accentFreq, normalFreq, twoPi, sr,
                                                    absFrame);
                    }
                }
            }
        }

        if (sample == 0.0f) continue;

        float* frameOut = out + static_cast<std::ptrdiff_t>(i) * channels;
        for (int c = 0; c < channels; ++c) frameOut[c] += sample;
    }
}
