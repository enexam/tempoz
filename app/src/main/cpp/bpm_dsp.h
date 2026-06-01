#pragma once

#include <cstdint>
#include <vector>

/**
 * Portable BPM / first-downbeat / beats-per-bar detection.
 *
 * Direct C++ port of the validated playground pipeline
 * (playground/pipeline/{onset,tempo,phase,downbeat}.py). Pure DSP, no Android
 * dependencies: operates on a mono float buffer at a fixed working sample rate
 * (44100, so the playground constants apply unchanged).
 */

struct DspResult {
    double  bpm;              // fractional BPM, 0.0 on failure
    int64_t firstBeatFrames;  // first downbeat, in working-rate samples
    int     beatsPerBar;      // bar group size (accent period)
};

/**
 * Analyze [mono] (sampled at [sampleRate], expected 44100) and return the
 * detected tempo, first-downbeat sample offset, and beats-per-bar.
 * Returns {0.0, 0, 4} when the input is too short or no tempo is found.
 */
DspResult analyzeBpmDsp(const std::vector<float>& mono, int sampleRate);
