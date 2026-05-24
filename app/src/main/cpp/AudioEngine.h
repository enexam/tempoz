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
};
