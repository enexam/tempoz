#include "AudioEngine.h"

#include <algorithm>
#include <cstring>
#include <ctime>

#include <android/log.h>

#define LOG_TAG "AudioEngine"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// The engine runs entirely at this rate. Oboe's sample-rate conversion presents
// the callback at kSampleRate regardless of the device's native rate, so every
// frame count in the engine (playhead, duration, click grid, decoder output) is
// in the same unit.
static constexpr int kSampleRate   = 48000;
static constexpr int kChannelCount = 2;

// CLOCK_MONOTONIC now, in nanoseconds. Backed by the vDSO on Android, so it is
// safe to call from the audio callback (no syscall, no lock).
static int64_t nowMonotonicNanos() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

AudioEngine::AudioEngine() = default;

AudioEngine::~AudioEngine() {
    mIsPlaying.store(false, std::memory_order_relaxed);
    mFileDecoder.stop();
    if (mStream) {
        mStream->stop();
        mStream->close();
        mStream.reset();
    }
}

bool AudioEngine::openStreamIfNeeded() {
    if (mStream) return true;

    oboe::AudioStreamBuilder builder;
    builder.setDataCallback(this)
           ->setSharingMode(oboe::SharingMode::Shared)
           ->setPerformanceMode(oboe::PerformanceMode::None)
           ->setFormat(oboe::AudioFormat::Float)
           ->setChannelCount(kChannelCount)
           ->setSampleRate(kSampleRate)
           ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium);

    oboe::Result result = builder.openStream(mStream);
    if (result != oboe::Result::OK || !mStream) {
        LOGE("openStream failed: %s", oboe::convertToText(result));
        mStream.reset();
        return false;
    }
    if (mStream->getSampleRate() != kSampleRate) {
        // Should not happen with SR conversion enabled, but log if a backend
        // ever ignores it — the playhead math assumes kSampleRate.
        LOGE("stream opened at %d Hz, expected %d", mStream->getSampleRate(), kSampleRate);
    }
    return true;
}

void AudioEngine::loadFile(int fd, int64_t offset, int64_t length) {
    // Fully stop any current playback before swapping the file.
    stopInternal(/*resetPosition=*/true);

    bool ok = mFileDecoder.open(fd, offset, length, kSampleRate, kChannelCount);
    if (!ok) {
        LOGE("FileDecoder::open failed");
        mFileLoaded.store(false, std::memory_order_relaxed);
        return;
    }
    mFileLoaded.store(true, std::memory_order_relaxed);
    mPositionFrames.store(0, std::memory_order_relaxed);
    mEnded.store(false, std::memory_order_relaxed);
}

void AudioEngine::play() {
    if (!mFileLoaded.load(std::memory_order_relaxed)) return;
    if (mIsPlaying.load(std::memory_order_relaxed)) return;
    if (!openStreamIfNeeded()) return;

    mEnded.store(false, std::memory_order_relaxed);

    // Seed the decoder at the current playhead and prime the FIFO. Safe to touch
    // the FIFO: the stream is not started, so the callback is not consuming.
    mFileDecoder.startAt(static_cast<uint64_t>(mPositionFrames.load(std::memory_order_relaxed)));

    // Seed the interpolation snapshot so getAudiblePositionMs() is sane before
    // the first callback fires, and force a latency refresh.
    mLastCbFrames.store(mPositionFrames.load(std::memory_order_relaxed), std::memory_order_relaxed);
    mLastCbNanos.store(nowMonotonicNanos(), std::memory_order_relaxed);
    mLatencyStampNanos = 0;

    // Set playing before start() so the first callback renders audio.
    mIsPlaying.store(true, std::memory_order_relaxed);
    oboe::Result result = mStream->start();
    if (result != oboe::Result::OK) {
        LOGE("stream start failed: %s", oboe::convertToText(result));
        mIsPlaying.store(false, std::memory_order_relaxed);
        mFileDecoder.stop();
    }
}

void AudioEngine::resume() {
    play();
}

void AudioEngine::pause() {
    if (!mIsPlaying.load(std::memory_order_relaxed)) return;

    // Stop the callback from touching the FIFO, then stop the stream as a
    // barrier (it blocks until the callback has exited). The stream stays open.
    mIsPlaying.store(false, std::memory_order_relaxed);
    if (mStream) {
        mStream->stop();
    }
    // Cancel any active pre-roll: the next resume() will call play() which
    // never arms count-in, so playback resumes at mPositionFrames=0 immediately.
    mInCountIn.store(false, std::memory_order_relaxed);
    // Free the decode thread/codec; the playhead is left frozen where the audio
    // thread last advanced it.
    mFileDecoder.stop();
}

void AudioEngine::seekTo(int64_t positionMs) {
    // positionMs is in the OUTPUT timeline (what the listener hears).
    // Convert to output frames, clamp to output duration.
    const double s = static_cast<double>(mSpeed.load(std::memory_order_relaxed));
    int64_t outFrame = positionMs * kSampleRate / 1000;
    if (outFrame < 0) outFrame = 0;
    const int64_t srcDurFrames = mFileDecoder.getDurationFrames();
    const int64_t outDurFrames = (s > 0.0 && srcDurFrames > 0)
            ? static_cast<int64_t>(static_cast<double>(srcDurFrames) / s) : srcDurFrames;
    if (outDurFrames > 0 && outFrame > outDurFrames) outFrame = outDurFrames;

    const bool wasPlaying = mIsPlaying.load(std::memory_order_relaxed);

    // Drop out of the callback's FIFO path and stop the stream before any reseed.
    if (wasPlaying) {
        mIsPlaying.store(false, std::memory_order_relaxed);
        if (mStream) {
            mStream->stop();
        }
    }

    // Cancel any active pre-roll; the seek position is the new playhead.
    mInCountIn.store(false, std::memory_order_relaxed);
    mEnded.store(false, std::memory_order_relaxed);
    mPositionFrames.store(outFrame, std::memory_order_relaxed);

    if (wasPlaying) {
        // Reseed the decoder at the output frame position. startAt() converts
        // output→source internally (sourceFrame = outputFrame * speed).
        mFileDecoder.startAt(static_cast<uint64_t>(outFrame));
        mIsPlaying.store(true, std::memory_order_relaxed);
        if (mStream) {
            oboe::Result result = mStream->start();
            if (result != oboe::Result::OK) {
                LOGE("seek: stream start failed: %s", oboe::convertToText(result));
                mIsPlaying.store(false, std::memory_order_relaxed);
                mFileDecoder.stop();
            }
        }
    } else {
        // Paused or stopped: just halt the decoder so no stale thread holds the
        // old position. The next play()/resume() seeds at the new playhead.
        mFileDecoder.stop();
    }
}

void AudioEngine::seekToStart() {
    seekTo(0);
}

void AudioEngine::stop() {
    stopInternal(/*resetPosition=*/true);
}

void AudioEngine::stopInternal(bool resetPosition) {
    mIsPlaying.store(false, std::memory_order_relaxed);
    if (mStream) {
        mStream->stop();  // keep the stream open for reuse; just stop it
    }
    mInCountIn.store(false, std::memory_order_relaxed);
    mFileDecoder.stop();
    mEnded.store(false, std::memory_order_relaxed);
    if (resetPosition) {
        mPositionFrames.store(0, std::memory_order_relaxed);
    }
}

bool AudioEngine::isPlaying() {
    return mIsPlaying.load(std::memory_order_relaxed);
}

bool AudioEngine::isEnded() {
    return mEnded.load(std::memory_order_relaxed);
}

int64_t AudioEngine::getDurationMs() {
    // Duration in the OUTPUT timeline (at the speaker) = sourceDuration / speed.
    // mPositionFrames and the user-facing timeline are OUTPUT frames.
    const double s = static_cast<double>(mSpeed.load(std::memory_order_relaxed));
    const int64_t srcFrames = mFileDecoder.getDurationFrames();
    if (s <= 0.0) return 0;
    return static_cast<int64_t>(static_cast<double>(srcFrames) / s) * 1000LL / kSampleRate;
}

int64_t AudioEngine::getPositionMs() {
    // mPositionFrames is in OUTPUT frames; clamp to the output duration.
    int64_t frames = mPositionFrames.load(std::memory_order_relaxed);
    const double s = static_cast<double>(mSpeed.load(std::memory_order_relaxed));
    const int64_t srcFrames = mFileDecoder.getDurationFrames();
    const int64_t outDurFrames = (s > 0.0)
            ? static_cast<int64_t>(static_cast<double>(srcFrames) / s) : srcFrames;
    if (outDurFrames > 0 && frames > outDurFrames) frames = outDurFrames;
    return frames * 1000LL / kSampleRate;
}

int64_t AudioEngine::getAudiblePositionMs() {
    // During count-in the file position is fixed at 0; report it directly.
    if (mInCountIn.load(std::memory_order_relaxed)) {
        return 0;
    }
    // When not actively playing the playhead is frozen; no interpolation.
    if (!mIsPlaying.load(std::memory_order_relaxed) || !mStream) {
        return getPositionMs();
    }

    const int64_t lastFrames = mLastCbFrames.load(std::memory_order_relaxed);
    const int64_t lastNanos  = mLastCbNanos.load(std::memory_order_relaxed);
    const int64_t now = nowMonotonicNanos();
    int64_t elapsedNs = now - lastNanos;
    if (elapsedNs < 0) elapsedNs = 0;

    // Interpolate the write playhead forward to 'now'.
    double frames = static_cast<double>(lastFrames)
                    + static_cast<double>(elapsedNs) * static_cast<double>(kSampleRate) / 1e9;

    // Refresh the cached output latency at most ~twice a second.
    if (now - mLatencyStampNanos > 500000000LL) {
        auto latency = mStream->calculateLatencyMillis();
        if (latency) mCachedLatencyMs = latency.value();
        mLatencyStampNanos = now;
    }

    // Subtract output latency to get the frame currently at the speaker.
    frames -= mCachedLatencyMs * static_cast<double>(kSampleRate) / 1000.0;
    if (frames < 0.0) frames = 0.0;

    const double s = static_cast<double>(mSpeed.load(std::memory_order_relaxed));
    const int64_t srcFrames = mFileDecoder.getDurationFrames();
    const double outDurFrames = (s > 0.0)
            ? static_cast<double>(srcFrames) / s : static_cast<double>(srcFrames);
    if (outDurFrames > 0.0 && frames > outDurFrames) {
        frames = outDurFrames;
    }
    return static_cast<int64_t>(frames * 1000.0 / static_cast<double>(kSampleRate));
}

void AudioEngine::setBpm(double bpm) {
    mBpm.store(bpm, std::memory_order_relaxed);
}

void AudioEngine::setBeatsPerBar(int beatsPerBar) {
    mBeatsPerBar.store(beatsPerBar, std::memory_order_relaxed);
}

void AudioEngine::setTrackVolume(float volume) {
    mTrackVolume.store(volume, std::memory_order_relaxed);
}

void AudioEngine::setClickVolume(float volume) {
    mClickVolume.store(volume, std::memory_order_relaxed);
}

void AudioEngine::setFirstBeatOffset(int64_t frames) {
    mFirstBeatOffset.store(frames, std::memory_order_relaxed);
}

void AudioEngine::setClickSound(int id) {
    mClickSound.store(id, std::memory_order_relaxed);
}

void AudioEngine::setSubdivision(int subdivision) {
    mSubdivision.store(subdivision, std::memory_order_relaxed);
}

void AudioEngine::setGhostVolume(float volume) {
    mGhostVolume.store(volume, std::memory_order_relaxed);
}

void AudioEngine::setCountInBars(int bars) {
    if (bars < 0) bars = 0;
    mCountInBars.store(bars, std::memory_order_relaxed);
}

void AudioEngine::setSpeed(float speed) {
    if (speed < 0.5f) speed = 0.5f;
    if (speed > 1.5f) speed = 1.5f;

    // Remap the current output playhead to the same source position at the new
    // speed before storing, so a live speed change doesn't jump the timeline.
    // Unit model: outputFrame = sourceFrame / s  →  sourceFrame = outputFrame * s
    //             newOutputFrame = sourceFrame / newS = outputFrame * oldS / newS
    const float oldS = mSpeed.load(std::memory_order_relaxed);
    const int64_t outFrameOld = mPositionFrames.load(std::memory_order_relaxed);
    const int64_t outFrameNew = (speed > 0.0f && oldS > 0.0f)
            ? std::llround(static_cast<double>(outFrameOld) * static_cast<double>(oldS)
                           / static_cast<double>(speed))
            : outFrameOld;

    mSpeed.store(speed, std::memory_order_relaxed);
    mFileDecoder.setSpeed(speed);
    mPositionFrames.store(outFrameNew, std::memory_order_relaxed);

    if (!mFileLoaded.load(std::memory_order_relaxed)) return;

    const bool wasPlaying = mIsPlaying.load(std::memory_order_relaxed);
    if (wasPlaying) {
        // Stop-barrier so the audio callback is not accessing the FIFO.
        mIsPlaying.store(false, std::memory_order_relaxed);
        if (mStream) {
            mStream->stop();
        }
        // Reseed the decoder at the new output frame (startAt converts to source
        // internally), recreating the Sonic stream at the new speed.
        mFileDecoder.startAt(static_cast<uint64_t>(outFrameNew));
        mIsPlaying.store(true, std::memory_order_relaxed);
        if (mStream) {
            mStream->start();
        }
    }
}

void AudioEngine::startWithCountIn() {
    if (!mFileLoaded.load(std::memory_order_relaxed)) return;
    if (mIsPlaying.load(std::memory_order_relaxed)) return;
    if (!openStreamIfNeeded()) return;

    mEnded.store(false, std::memory_order_relaxed);

    const int bars = mCountInBars.load(std::memory_order_relaxed);
    if (bars > 0) {
        const double bpm = mBpm.load(std::memory_order_relaxed);
        const float speed = mSpeed.load(std::memory_order_relaxed);
        // Use effectiveBpm so the count-in pre-roll duration matches the
        // stretched-track tempo and leads into the downbeat seamlessly.
        const double effectiveBpm = bpm * static_cast<double>(speed);
        const int beatsPerBar = mBeatsPerBar.load(std::memory_order_relaxed);
        const double interval = static_cast<double>(kSampleRate) * 60.0 / effectiveBpm;
        const int64_t totalBeats = static_cast<int64_t>(bars) * beatsPerBar;
        const int64_t totalFrames = std::llround(static_cast<double>(totalBeats) * interval);
        mCountInTotalFrames.store(totalFrames, std::memory_order_relaxed);
        mCountInElapsed.store(0, std::memory_order_relaxed);
        mInCountIn.store(true, std::memory_order_relaxed);
    } else {
        mInCountIn.store(false, std::memory_order_relaxed);
    }

    // Seed the decoder at frame 0 so it is ready when the pre-roll ends (or
    // immediately if there is no pre-roll). The FIFO is safe to touch: the
    // stream is not started yet and the callback is not running.
    mPositionFrames.store(0, std::memory_order_relaxed);
    mFileDecoder.startAt(0);

    mLastCbFrames.store(0, std::memory_order_relaxed);
    mLastCbNanos.store(nowMonotonicNanos(), std::memory_order_relaxed);
    mLatencyStampNanos = 0;

    mIsPlaying.store(true, std::memory_order_relaxed);
    oboe::Result result = mStream->start();
    if (result != oboe::Result::OK) {
        LOGE("startWithCountIn: stream start failed: %s", oboe::convertToText(result));
        mIsPlaying.store(false, std::memory_order_relaxed);
        mInCountIn.store(false, std::memory_order_relaxed);
        mFileDecoder.stop();
    }
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream* /*oboeStream*/,
                                                    void* audioData,
                                                    int32_t numFrames) {
    const int channels = kChannelCount;
    const int totalSamples = numFrames * channels;
    float* output = static_cast<float*>(audioData);

    const bool playing = mIsPlaying.load(std::memory_order_relaxed)
                         && !mEnded.load(std::memory_order_relaxed);

    // When not playing (paused mid-stop, or ended) output silence and do not
    // touch the FIFO or advance the playhead. This is also the guard that keeps
    // the FIFO single-consumer during a reseed.
    if (!playing) {
        std::memset(output, 0, static_cast<size_t>(totalSamples) * sizeof(float));
        return oboe::DataCallbackResult::Continue;
    }

    // ---- Count-in pre-roll ----
    // While mInCountIn is true, render the click as a pure function of the
    // pre-roll counter (firstBeatOffset=0 so beat 0 lands immediately), keep
    // the file silent, and do NOT advance mPositionFrames. When the counter
    // runs out, clear the flag and fall through to the normal playback path
    // for the remainder of this block (zero-gap transition to frame 0).
    if (mInCountIn.load(std::memory_order_relaxed)) {
        const double bpm = mBpm.load(std::memory_order_relaxed);
        const float speed = mSpeed.load(std::memory_order_relaxed);
        // effectiveBpm accounts for the time-stretch so the count-in tempo
        // matches the stretched track and leads in seamlessly.
        const double effectiveBpm = bpm * static_cast<double>(speed);
        const int beatsPerBar = mBeatsPerBar.load(std::memory_order_relaxed);
        const float clickVol = mClickVolume.load(std::memory_order_relaxed);
        const int clickSound = mClickSound.load(std::memory_order_relaxed);
        const int subdivision = mSubdivision.load(std::memory_order_relaxed);
        const float ghostVolume = mGhostVolume.load(std::memory_order_relaxed);
        const int64_t elapsed = mCountInElapsed.load(std::memory_order_relaxed);
        const int64_t total = mCountInTotalFrames.load(std::memory_order_relaxed);

        // How many frames of this block still belong to the pre-roll.
        const int64_t remaining = total - elapsed;
        const int countInFrames = (remaining >= numFrames)
                                  ? numFrames
                                  : static_cast<int>(remaining < 0 ? 0 : remaining);

        std::memset(output, 0, static_cast<size_t>(totalSamples) * sizeof(float));
        if (countInFrames > 0) {
            // Reuse totalSamples-sized stack buffer (countInFrames <= numFrames).
            float clickBuf[totalSamples];
            std::memset(clickBuf, 0, static_cast<size_t>(totalSamples) * sizeof(float));
            // Use effectiveBpm so count-in tempo matches the stretched track.
            mClickGenerator.render(clickBuf, countInFrames, channels,
                                   elapsed, effectiveBpm, beatsPerBar,
                                   /*firstBeatOffset=*/0, kSampleRate, 1.0f,
                                   clickSound, subdivision, ghostVolume);
            for (int i = 0; i < countInFrames * channels; ++i) {
                output[i] = std::clamp(clickVol * clickBuf[i], -1.0f, 1.0f);
            }
        }

        const int64_t newElapsed = elapsed + countInFrames;
        mCountInElapsed.store(newElapsed, std::memory_order_relaxed);

        if (newElapsed >= total) {
            // Pre-roll complete. Clear the flag and render the trailing frames of
            // this block (frames after the count-in portion) as normal playback so
            // the downbeat starts in the same callback — zero-gap, sample-accurate.
            mInCountIn.store(false, std::memory_order_relaxed);

            const int trailingFrames = numFrames - countInFrames;
            if (trailingFrames > 0) {
                const int trailingSamples = trailingFrames * channels;
                const int64_t startFrame = mPositionFrames.load(std::memory_order_relaxed);
                const float trackVol = mTrackVolume.load(std::memory_order_relaxed);
                // mFirstBeatOffset is in SOURCE frames; effectiveOffset is the
                // corresponding position in the OUTPUT timeline (output = source / speed).
                const int64_t firstBeat = mFirstBeatOffset.load(std::memory_order_relaxed);
                const int64_t effectiveOffset = (speed > 0.0f)
                        ? std::llround(static_cast<double>(firstBeat) / static_cast<double>(speed))
                        : firstBeat;
                float* trailingOut = output + countInFrames * channels;

                float fileBuf[trailingSamples];
                float clickBuf2[trailingSamples];

                if (mFileLoaded.load(std::memory_order_relaxed)) {
                    mFileDecoder.read(fileBuf, trailingFrames);
                } else {
                    std::memset(fileBuf, 0, static_cast<size_t>(trailingSamples) * sizeof(float));
                }

                std::memset(clickBuf2, 0, static_cast<size_t>(trailingSamples) * sizeof(float));
                // Use effectiveBpm and effectiveOffset to lock the click to the
                // stretched audio.
                mClickGenerator.render(clickBuf2, trailingFrames, channels,
                                       startFrame, effectiveBpm, beatsPerBar,
                                       effectiveOffset, kSampleRate, 1.0f,
                                       clickSound, subdivision, ghostVolume);

                for (int i = 0; i < trailingSamples; ++i) {
                    trailingOut[i] = std::clamp(trackVol * fileBuf[i] + clickVol * clickBuf2[i],
                                                -1.0f, 1.0f);
                }

                const int64_t newPos = startFrame + trailingFrames;
                mPositionFrames.store(newPos, std::memory_order_relaxed);
                mLastCbFrames.store(newPos, std::memory_order_relaxed);
                mLastCbNanos.store(nowMonotonicNanos(), std::memory_order_relaxed);
                if (mFileLoaded.load(std::memory_order_relaxed) && mFileDecoder.isEOF()) {
                    mEnded.store(true, std::memory_order_relaxed);
                }
            } else {
                mLastCbFrames.store(mPositionFrames.load(std::memory_order_relaxed),
                                    std::memory_order_relaxed);
                mLastCbNanos.store(nowMonotonicNanos(), std::memory_order_relaxed);
            }
        } else {
            mLastCbFrames.store(mPositionFrames.load(std::memory_order_relaxed),
                                std::memory_order_relaxed);
            mLastCbNanos.store(nowMonotonicNanos(), std::memory_order_relaxed);
        }

        return oboe::DataCallbackResult::Continue;
    }

    const int64_t startFrame = mPositionFrames.load(std::memory_order_relaxed);
    const float trackVol = mTrackVolume.load(std::memory_order_relaxed);
    const float clickVol = mClickVolume.load(std::memory_order_relaxed);
    const double bpm = mBpm.load(std::memory_order_relaxed);
    const float speed = mSpeed.load(std::memory_order_relaxed);
    const int beatsPerBar = mBeatsPerBar.load(std::memory_order_relaxed);
    const int64_t firstBeat = mFirstBeatOffset.load(std::memory_order_relaxed);
    const int clickSound = mClickSound.load(std::memory_order_relaxed);
    const int subdivision = mSubdivision.load(std::memory_order_relaxed);
    const float ghostVolume = mGhostVolume.load(std::memory_order_relaxed);

    // Effective tempo and offset in the OUTPUT timeline.
    // mFirstBeatOffset is stored in SOURCE frames; OUTPUT = SOURCE / speed.
    // effectiveBpm = bpm * speed keeps the click locked to the stretched audio.
    const double effectiveBpm = bpm * static_cast<double>(speed);
    const int64_t effectiveOffset = (speed > 0.0f)
            ? std::llround(static_cast<double>(firstBeat) / static_cast<double>(speed))
            : firstBeat;

    float fileBuf[totalSamples];
    float clickBuf[totalSamples];

    if (mFileLoaded.load(std::memory_order_relaxed)) {
        mFileDecoder.read(fileBuf, numFrames);
    } else {
        std::memset(fileBuf, 0, static_cast<size_t>(totalSamples) * sizeof(float));
    }

    // Click is a pure function of the absolute output playhead, so it stays locked
    // to the file's beat grid (in the output timeline) across seeks and pauses.
    std::memset(clickBuf, 0, static_cast<size_t>(totalSamples) * sizeof(float));
    mClickGenerator.render(clickBuf, numFrames, channels, startFrame,
                           effectiveBpm, beatsPerBar,
                           effectiveOffset, kSampleRate, 1.0f,
                           clickSound, subdivision, ghostVolume);

    for (int i = 0; i < totalSamples; ++i) {
        output[i] = std::clamp(trackVol * fileBuf[i] + clickVol * clickBuf[i], -1.0f, 1.0f);
    }

    // Advance the single playhead, then detect end-of-file.
    const int64_t newPos = startFrame + numFrames;
    mPositionFrames.store(newPos, std::memory_order_relaxed);
    // Snapshot (playhead, time) so the UI can interpolate the audible position
    // between callbacks.
    mLastCbFrames.store(newPos, std::memory_order_relaxed);
    mLastCbNanos.store(nowMonotonicNanos(), std::memory_order_relaxed);
    if (mFileLoaded.load(std::memory_order_relaxed) && mFileDecoder.isEOF()) {
        mEnded.store(true, std::memory_order_relaxed);
    }

    return oboe::DataCallbackResult::Continue;
}
