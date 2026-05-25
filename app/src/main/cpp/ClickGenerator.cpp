#include "ClickGenerator.h"
#include <algorithm>
#include <cmath>

void ClickGenerator::configure(int bpm, int beatsPerBar, int sampleRate, int64_t initialOffsetFrames) {
    mBpm = bpm;
    mBeatsPerBar = beatsPerBar;
    mSampleRate = sampleRate;
    mBeatIntervalFrames = static_cast<int64_t>(sampleRate) * 60 / bpm;
    mClickDurationFrames = static_cast<int>(kClickDurationSeconds * sampleRate);

    // Apply the offset, clamped to >= 0. 0 fires a beat on the very first rendered frame.
    mFramesUntilNextBeat = std::max(static_cast<int64_t>(0), initialOffsetFrames);
    mCurrentBeat = 0;
    mClickFramesRemaining = 0;
    mPhase = 0.0f;
    mFreq = 0.0f;
}

void ClickGenerator::render(float* out, int numFrames, int channels, float gain) {
    const float twoPi = 2.0f * static_cast<float>(M_PI);
    const float sampleRateF = static_cast<float>(mSampleRate);

    for (int frame = 0; frame < numFrames; ++frame) {
        // Fire a beat when the countdown reaches zero.
        if (mFramesUntilNextBeat == 0) {
            mFreq = (mCurrentBeat == 0) ? kAccentFreq : kNormalFreq;
            mCurrentBeat = (mCurrentBeat + 1) % mBeatsPerBar;
            mClickFramesRemaining = mClickDurationFrames;
            mPhase = 0.0f;
            mFramesUntilNextBeat = mBeatIntervalFrames - 1;
        } else {
            --mFramesUntilNextBeat;
        }

        float sample = 0.0f;
        if (mClickFramesRemaining > 0) {
            // Elapsed time in seconds since this click started.
            float t = static_cast<float>(mClickDurationFrames - mClickFramesRemaining) / sampleRateF;
            float envelope = gain * std::exp(-kDecay * t);
            sample = envelope * std::sin(mPhase);
            mPhase += twoPi * mFreq / sampleRateF;
            if (mPhase >= twoPi) mPhase -= twoPi;
            --mClickFramesRemaining;
        }

        // Write to all channels (interleaved).
        for (int ch = 0; ch < channels; ++ch) {
            out[frame * channels + ch] += sample;
        }
    }
}
