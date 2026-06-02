#pragma once

#include <atomic>
#include <memory>

#include <oboe/Oboe.h>

#include "ClickGenerator.h"
#include "FileDecoder.h"

/**
 * Oboe audio engine that mixes a decoded audio file with a synthesized
 * metronome click, driven by a single deterministic state machine.
 *
 * Design invariants — these are what make playback reliable:
 *   - The Oboe stream is opened once and kept open for the engine's lifetime;
 *     pause/resume/seek only stop()/start() it, never close it. Repeated
 *     open/close of the stream is what made the seeker fail.
 *   - The stream is fixed at kSampleRate via Oboe's built-in sample-rate
 *     conversion, so every frame count — playhead, duration, click grid — is in
 *     one unit and the click cannot drift against the file on non-48k devices.
 *   - There is exactly one playhead, mPositionFrames: advanced by the audio
 *     thread while playing, written by seek/stop only while the stream is
 *     stopped (callback not running). STOPPED → 0, PAUSED → frozen.
 *   - The audio callback reads the decoder FIFO only when mIsPlaying is true.
 *     Every reseed sets mIsPlaying=false and stops the stream first, so the FIFO
 *     stays strictly single-producer / single-consumer.
 *   - The callback never touches mStream and never returns Stop; lifecycle is
 *     owned by the main thread.
 *
 * All public methods are called from the main thread, serialized by the
 * ViewModel. onAudioReady runs on the Oboe audio thread.
 */
class AudioEngine : public oboe::AudioStreamDataCallback {
public:
    AudioEngine();
    ~AudioEngine() override;

    /** Load an audio file. Stops any current playback and resets the playhead. */
    void loadFile(int fd, int64_t offset, int64_t length);

    /** Start playback from the current playhead (from 0 when stopped). */
    void play();

    /** Pause playback, freezing the playhead. */
    void pause();

    /** Alias for play(); resumes from the frozen playhead. */
    void resume();

    /** Seek to positionMs, preserving the current play/pause state. */
    void seekTo(int64_t positionMs);

    /** Seek back to the start, preserving the current play/pause state. */
    void seekToStart();

    /** Stop playback and reset the playhead to 0. Keeps the stream open. */
    void stop();

    /** True while actively playing (false when paused, stopped, or ended). */
    bool isPlaying();

    /** True once the file has played to its end. Cleared by seek/play/loadFile. */
    bool isEnded();

    int64_t getDurationMs();
    int64_t getPositionMs();

    /**
     * Position of the frame currently being heard at the speaker, in ms — the
     * write playhead interpolated from the last callback's monotonic timestamp,
     * minus the stream's output latency. Used to lock the visual beat to the
     * audible click. Falls back to getPositionMs() when not playing.
     */
    int64_t getAudiblePositionMs();

    void setBpm(double bpm);
    void setBeatsPerBar(int beatsPerBar);
    void setTrackVolume(float volume);
    void setClickVolume(float volume);

    /** Absolute frame of the first beat in the file (from analysis), at kSampleRate. */
    void setFirstBeatOffset(int64_t frames);

    /**
     * Select the synthesized click voice (0–5, matching ClickSoundOptions order):
     * 0=Click, 1=Rim, 2=Wood block, 3=Beep, 4=Cowbell, 5=Hi-hat.
     * Applied atomically; takes effect on the next audio callback.
     */
    void setClickSound(int id);

    /**
     * Set the subdivision (1=quarter/off, 2=eighth, 3=triplet, 4=sixteenth).
     * Applied atomically; takes effect on the next audio callback.
     */
    void setSubdivision(int subdivision);

    /**
     * Set the relative gain of ghost (sub-beat) clicks in [0, 1].
     * Applied atomically; takes effect on the next audio callback.
     */
    void setGhostVolume(float volume);

    /**
     * Set the number of count-in bars before playback (0 = disabled).
     * Applied atomically; used the next time startWithCountIn() is called.
     */
    void setCountInBars(int bars);

    /**
     * Set the playback speed factor, clamped to [0.5, 1.5].
     * Forwards to FileDecoder for time-stretching the audio track.
     * The click grid uses effectiveBpm = bpm·s and effectiveOffset = firstBeatOffset/s
     * so the click stays locked to the stretched audio.
     * A live change causes minor timeline reflow (acceptable per spec).
     */
    void setSpeed(float speed);

    /**
     * Like play(), but prepends a count-in pre-roll if mCountInBars > 0.
     * The pre-roll renders N*beatsPerBar clicks at the current bpm/sig with
     * the file silent, then transitions seamlessly into normal playback at
     * mPositionFrames=0. One-shot: only this entry point arms count-in;
     * resume()/seekTo(0)/loop (which calls seekTo(0)) never arm it.
     */
    void startWithCountIn();

    // oboe::AudioStreamDataCallback
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* oboeStream,
                                          void* audioData,
                                          int32_t numFrames) override;

private:
    /** Open the persistent Oboe stream if not already open. Returns success. */
    bool openStreamIfNeeded();

    /** Stop the stream (kept open) and the decoder; optionally reset the playhead. */
    void stopInternal(bool resetPosition);

    ClickGenerator mClickGenerator;
    FileDecoder    mFileDecoder;

    std::shared_ptr<oboe::AudioStream> mStream;

    std::atomic<double>  mBpm{120.0};
    std::atomic<int>     mBeatsPerBar{4};
    std::atomic<float>   mTrackVolume{1.0f};
    std::atomic<float>   mClickVolume{1.0f};
    std::atomic<int64_t> mFirstBeatOffset{0};

    std::atomic<int>     mClickSound{0};
    std::atomic<int>     mSubdivision{1};
    std::atomic<float>   mGhostVolume{0.35f};

    std::atomic<int>     mCountInBars{0};

    // Playback speed: the time-stretch factor applied to the decoded audio.
    // The click grid uses effectiveBpm = mBpm * mSpeed and
    // effectiveOffset = round(mFirstBeatOffset / mSpeed) so it stays locked
    // to the stretched audio. mFirstBeatOffset is always stored in SOURCE frames.
    // Clamped to [0.5, 1.5] by setSpeed().
    std::atomic<float>   mSpeed{1.0f};

    // Count-in pre-roll state. Set/cleared on the main thread while the stream
    // is stopped (in startWithCountIn/pause/seekTo/stopInternal); read+written
    // by the audio thread while playing. mInCountIn gates the pre-roll branch in
    // onAudioReady; mCountInElapsed counts frames through the pre-roll.
    std::atomic<bool>    mInCountIn{false};
    std::atomic<int64_t> mCountInTotalFrames{0};
    std::atomic<int64_t> mCountInElapsed{0};

    std::atomic<bool>    mIsPlaying{false};
    std::atomic<bool>    mEnded{false};
    std::atomic<bool>    mFileLoaded{false};

    // The single playhead, in kSampleRate frames. Advanced by the audio thread
    // while playing; written by seek/stop only while the stream is stopped.
    std::atomic<int64_t> mPositionFrames{0};

    // Snapshot of (playhead, CLOCK_MONOTONIC nanos) taken at the end of each
    // audio callback, so getAudiblePositionMs() can interpolate the playhead
    // between callbacks. Written on the audio thread, read on the UI thread.
    std::atomic<int64_t> mLastCbFrames{0};
    std::atomic<int64_t> mLastCbNanos{0};

    // Cached output latency (ms) and when it was last refreshed. UI thread only
    // (getAudiblePositionMs is called from the Compose frame loop, serialized
    // with the lifecycle methods on the main thread).
    double  mCachedLatencyMs{0.0};
    int64_t mLatencyStampNanos{0};
};
