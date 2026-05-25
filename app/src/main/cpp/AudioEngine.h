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

    void setBpm(int bpm);
    void setBeatsPerBar(int beatsPerBar);
    void setTrackVolume(float volume);
    void setClickVolume(float volume);

    // oboe::AudioStreamDataCallback
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* oboeStream,
                                          void* audioData,
                                          int32_t numFrames) override;

private:
    ClickGenerator mClickGenerator;
    FileDecoder    mFileDecoder;

    std::shared_ptr<oboe::AudioStream> mStream;

    std::atomic<int>   mBpm{120};
    std::atomic<int>   mBeatsPerBar{4};
    std::atomic<float> mTrackVolume{1.0f};
    std::atomic<float> mClickVolume{1.0f};
    std::atomic<bool>  mIsPlaying{false};
    std::atomic<bool>  mFileLoaded{false};

    // File position (in output frames) saved by pause() for use by resume().
    uint64_t mPauseFrameOffset{0};

    // Last values forwarded to mClickGenerator; 0 forces configure() on the
    // first onAudioReady call.
    int mLastBpm{0};
    int mLastBeatsPerBar{0};
};
