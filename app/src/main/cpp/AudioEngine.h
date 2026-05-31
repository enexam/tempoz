#pragma once

#include <atomic>
#include <memory>

#include <oboe/Oboe.h>

#include "ClickGenerator.h"
#include "FileDecoder.h"

/**
 * Oboe audio engine that mixes a decoded audio file with a synthesized
 * metronome click track.
 *
 * Thread safety: all public setters are safe to call from any thread.
 * onAudioReady runs on the Oboe audio thread.
 */
class AudioEngine : public oboe::AudioStreamDataCallback {
public:
    AudioEngine();
    ~AudioEngine() override;

    /**
     * Load an audio file from the given file descriptor.
     * Uses 48000 Hz / 2 channels as defaults before the first stream is opened.
     * Must be called before start().
     */
    void loadFile(int fd, int64_t offset, int64_t length);

    /** Open and start the Oboe stream, the decode thread, and the click generator. */
    void start();

    /** Stop the Oboe stream and the decode thread. */
    void stop();

    /**
     * Pause playback: saves the current file position, stops the Oboe stream,
     * and stops the decode thread. Call resume() to continue.
     */
    void pause();

    /**
     * Resume playback from the position saved by pause(). Rebuilds the Oboe
     * stream, seeks the decoder, and restarts the decode thread.
     */
    void resume();

    /**
     * Stop playback and seek back to the beginning of the file, then restart.
     */
    void seekToStart();

    /** Returns true if the Oboe stream is currently playing. */
    bool isPlaying();

    /**
     * Returns the total duration of the loaded audio file in milliseconds.
     * Returns 0 if no file has been loaded or the format has no duration.
     */
    int64_t getDurationMs();

    /**
     * Returns the current playback position in milliseconds, accounting for
     * the accumulated pause offset and frames consumed since the last resume.
     */
    int64_t getPositionMs();

    /**
     * Seek to positionMs milliseconds from the start of the file. If playing,
     * stops the engine, seeks, and restarts. If paused/stopped, only seeks.
     */
    void seekTo(int64_t positionMs);

    void setBpm(double bpm);
    void setBeatsPerBar(int beatsPerBar);
    void setTrackVolume(float volume);
    void setClickVolume(float volume);

    /**
     * Set the absolute frame offset of the first beat in the audio file.
     * Used by start(), resume(), and seekTo() to align the click track to the
     * recording's natural beat grid.
     */
    void setFirstBeatOffset(int64_t frames);

    // oboe::AudioStreamDataCallback
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* oboeStream,
                                          void* audioData,
                                          int32_t numFrames) override;

private:
    ClickGenerator mClickGenerator;
    FileDecoder    mFileDecoder;

    std::shared_ptr<oboe::AudioStream> mStream;

    std::atomic<double> mBpm{120.0};
    std::atomic<int>   mBeatsPerBar{4};
    std::atomic<float> mTrackVolume{1.0f};
    std::atomic<float> mClickVolume{1.0f};
    std::atomic<bool>  mIsPlaying{false};
    std::atomic<bool>  mFileLoaded{false};

    // File position (in output frames) saved by pause() for use by resume().
    uint64_t mPauseFrameOffset{0};

    // Absolute frame position of the first beat in the audio file (from analysis).
    // 0 means the click fires on the first rendered frame (default behaviour).
    // Written on the main thread, read on the audio callback thread — must be atomic.
    std::atomic<int64_t> mFirstBeatOffset{0};

    // Last values forwarded to mClickGenerator; 0 forces configure() on the
    // first onAudioReady call.
    double mLastBpm{0.0};
    int mLastBeatsPerBar{0};

    /**
     * Compute how many frames until the next beat fires, given that playback
     * is about to start from @p currentFrameOffset.
     *
     * If currentFrameOffset < mFirstBeatOffset the pre-roll is the distance
     * to the first beat. Otherwise we phase into the beat grid.
     */
    int64_t computeFramesUntilBeat(int64_t currentFrameOffset, int streamRate) const;
};
