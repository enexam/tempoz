#include "AudioEngine.h"

#include <algorithm>
#include <cstring>

#include <android/log.h>

#define LOG_TAG "AudioEngine"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static constexpr int kDefaultSampleRate  = 48000;
static constexpr int kDefaultChannelCount = 2;

AudioEngine::AudioEngine() = default;

AudioEngine::~AudioEngine() {
    stop();
}

void AudioEngine::loadFile(int fd, int64_t offset, int64_t length) {
    int sampleRate   = kDefaultSampleRate;
    int channelCount = kDefaultChannelCount;

    if (mStream) {
        sampleRate   = mStream->getSampleRate();
        channelCount = mStream->getChannelCount();
    }

    bool ok = mFileDecoder.open(fd, offset, length, sampleRate, channelCount);
    if (!ok) {
        LOGE("FileDecoder::open failed");
        return;
    }
    mFileLoaded.store(true, std::memory_order_relaxed);
}

void AudioEngine::start() {
    // Build and open the Oboe stream.
    oboe::AudioStreamBuilder builder;
    builder.setDataCallback(this)
           ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
           ->setSharingMode(oboe::SharingMode::Exclusive)
           ->setFormat(oboe::AudioFormat::Float)
           ->setChannelCount(kDefaultChannelCount)
           ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium);

    oboe::Result result = builder.openStream(mStream);
    if (result != oboe::Result::OK) {
        LOGE("openStream failed: %s", oboe::convertToText(result));
        return;
    }

    // Start the file decoder if a file has been loaded.
    if (mFileLoaded.load(std::memory_order_relaxed)) {
        mFileDecoder.start();
    }

    // Configure the click generator with the stream's actual sample rate.
    mClickGenerator.configure(mBpm.load(std::memory_order_relaxed),
                               mBeatsPerBar.load(std::memory_order_relaxed),
                               mStream->getSampleRate());

    mIsPlaying.store(true, std::memory_order_relaxed);

    result = mStream->start();
    if (result != oboe::Result::OK) {
        LOGE("stream->start() failed: %s", oboe::convertToText(result));
        mIsPlaying.store(false, std::memory_order_relaxed);
    }
}

void AudioEngine::stop() {
    mIsPlaying.store(false, std::memory_order_relaxed);

    mFileDecoder.stop();

    if (mStream) {
        mStream->stop();
        mStream->close();
        mStream.reset();
    }
}

void AudioEngine::setBpm(int bpm) {
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

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream* /*oboeStream*/,
                                                    void* audioData,
                                                    int32_t numFrames) {
    const int channels = kDefaultChannelCount;
    const int totalSamples = numFrames * channels;

    float* output = static_cast<float*>(audioData);

    // Snapshot volumes once for this callback.
    const float trackVol = mTrackVolume.load(std::memory_order_relaxed);
    const float clickVol = mClickVolume.load(std::memory_order_relaxed);

    // Stack buffers for intermediate PCM.
    float fileBuf[totalSamples];
    float clickBuf[totalSamples];

    // Read decoded file audio (silence if no file loaded or decoder starved).
    if (mFileLoaded.load(std::memory_order_relaxed)) {
        mFileDecoder.read(fileBuf, numFrames);
    } else {
        memset(fileBuf, 0, static_cast<size_t>(totalSamples) * sizeof(float));
    }

    // ClickGenerator::render sums into existing content — zero first.
    memset(clickBuf, 0, static_cast<size_t>(totalSamples) * sizeof(float));
    mClickGenerator.render(clickBuf, numFrames, channels, 1.0f);

    // Mix and clamp.
    for (int i = 0; i < totalSamples; ++i) {
        float mixed = trackVol * fileBuf[i] + clickVol * clickBuf[i];
        output[i] = std::clamp(mixed, -1.0f, 1.0f);
    }

    // Stop when the file track reaches EOF.
    if (mFileLoaded.load(std::memory_order_relaxed) && mFileDecoder.isEOF()) {
        mIsPlaying.store(false, std::memory_order_relaxed);
        return oboe::DataCallbackResult::Stop;
    }

    return oboe::DataCallbackResult::Continue;
}
