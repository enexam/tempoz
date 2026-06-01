#include "BpmAnalyzer.h"

#include "bpm_dsp.h"

#include <android/log.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaFormat.h>

#include <cstring>
#include <vector>

#define LOG_TAG "BpmAnalyzer"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static constexpr int64_t kCodecTimeoutUs = 5000; // 5 ms
static constexpr int     kMaxSeconds     = 60;
static constexpr int     kWorkingRate    = 44100; // analysis rate (playground parity)

// ---------------------------------------------------------------------------
// PCM conversion helpers (mirrors FileDecoder::convertToFloat logic)
// ---------------------------------------------------------------------------

/**
 * Convert raw MediaCodec output bytes to mono float32 samples.
 * Appends to [out]. Stops appending when out.size() >= maxFrames.
 *
 * @param data        pointer to raw codec output
 * @param byteSize    size in bytes
 * @param pcmEncoding AMEDIAFORMAT_KEY_PCM_ENCODING value (-1 → assume int16)
 * @param channels    number of input channels
 * @param out         destination mono float vector
 * @param maxFrames   stop appending beyond this many frames
 */
static void appendMonoFrames(const uint8_t* data, size_t byteSize,
                             int pcmEncoding, int channels,
                             std::vector<float>& out, size_t maxFrames) {
    const int enc = (pcmEncoding == -1) ? 2 : pcmEncoding;

    if (enc == 4 /* float */) {
        const int frames = static_cast<int>(
                byteSize / (sizeof(float) * static_cast<size_t>(channels)));
        const auto* src = reinterpret_cast<const float*>(data);
        for (int f = 0; f < frames && out.size() < maxFrames; ++f) {
            float sum = 0.0f;
            for (int c = 0; c < channels; ++c) {
                sum += src[static_cast<size_t>(f * channels + c)];
            }
            out.push_back(sum / static_cast<float>(channels));
        }
    } else if (enc == 22 /* int32 */) {
        const int frames = static_cast<int>(
                byteSize / (sizeof(int32_t) * static_cast<size_t>(channels)));
        const auto* src = reinterpret_cast<const int32_t*>(data);
        constexpr float kScale = 1.0f / 2147483648.0f;
        for (int f = 0; f < frames && out.size() < maxFrames; ++f) {
            float sum = 0.0f;
            for (int c = 0; c < channels; ++c) {
                sum += static_cast<float>(src[static_cast<size_t>(f * channels + c)]) * kScale;
            }
            out.push_back(sum / static_cast<float>(channels));
        }
    } else {
        // Default: int16
        const int frames = static_cast<int>(
                byteSize / (sizeof(int16_t) * static_cast<size_t>(channels)));
        const auto* src = reinterpret_cast<const int16_t*>(data);
        constexpr float kScale = 1.0f / 32768.0f;
        for (int f = 0; f < frames && out.size() < maxFrames; ++f) {
            float sum = 0.0f;
            for (int c = 0; c < channels; ++c) {
                sum += static_cast<float>(src[static_cast<size_t>(f * channels + c)]) * kScale;
            }
            out.push_back(sum / static_cast<float>(channels));
        }
    }
}

// ---------------------------------------------------------------------------
// BpmAnalyzer::analyze()
// ---------------------------------------------------------------------------

BpmResult BpmAnalyzer::analyze(int fd, int64_t offset, int64_t length) {
    AMediaExtractor* extractor = nullptr;
    AMediaCodec*     codec     = nullptr;

    // Cleanup lambda — calls stop()+delete on codec (if started), delete on extractor.
    // codecStarted tracks whether AMediaCodec_start has been called.
    bool codecStarted = false;
    auto cleanup = [&]() {
        if (codec) {
            if (codecStarted) {
                AMediaCodec_stop(codec);
            }
            AMediaCodec_delete(codec);
            codec = nullptr;
        }
        if (extractor) {
            AMediaExtractor_delete(extractor);
            extractor = nullptr;
        }
    };

    // -------------------------------------------------------------------------
    // 1. Open extractor and locate the first audio track.
    // -------------------------------------------------------------------------
    extractor = AMediaExtractor_new();
    media_status_t status = AMediaExtractor_setDataSourceFd(
            extractor, fd,
            static_cast<off64_t>(offset),
            static_cast<off64_t>(length));
    if (status != AMEDIA_OK) {
        LOGE("setDataSourceFd failed: %d", status);
        cleanup();
        return {0, 0};
    }

    int numTracks  = static_cast<int>(AMediaExtractor_getTrackCount(extractor));
    int audioTrack = -1;
    int sampleRate = 0;
    int channels   = 0;
    int pcmEncoding = -1;
    std::string mimeStr;

    for (int i = 0; i < numTracks; ++i) {
        AMediaFormat* fmt = AMediaExtractor_getTrackFormat(extractor, i);
        const char* mime  = nullptr;
        AMediaFormat_getString(fmt, AMEDIAFORMAT_KEY_MIME, &mime);
        if (mime && strncmp(mime, "audio/", 6) == 0) {
            audioTrack = i;
            int32_t sr = 0, ch = 0, enc = -1;
            AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sr);
            AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &ch);
            AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_PCM_ENCODING, &enc);
            sampleRate  = sr;
            channels    = ch;
            pcmEncoding = enc;
            // Copy mime before deleting the format object.
            mimeStr = mime ? mime : "";
            AMediaFormat_delete(fmt);
            break;
        }
        AMediaFormat_delete(fmt);
    }

    if (audioTrack < 0 || sampleRate <= 0 || channels <= 0) {
        LOGE("No valid audio track: track=%d sr=%d ch=%d", audioTrack, sampleRate, channels);
        cleanup();
        return {0, 0};
    }

    AMediaExtractor_selectTrack(extractor, static_cast<size_t>(audioTrack));

    // -------------------------------------------------------------------------
    // 2. Create and configure the decoder.
    // -------------------------------------------------------------------------
    codec = AMediaCodec_createDecoderByType(mimeStr.c_str());
    if (!codec) {
        LOGE("createDecoderByType failed for mime: %s", mimeStr.c_str());
        cleanup();
        return {0, 0};
    }

    // Use a fresh track format for codec configuration.
    AMediaFormat* codecFmt = AMediaExtractor_getTrackFormat(
            extractor, static_cast<size_t>(audioTrack));
    status = AMediaCodec_configure(codec, codecFmt, nullptr, nullptr, 0);
    AMediaFormat_delete(codecFmt);
    if (status != AMEDIA_OK) {
        LOGE("AMediaCodec_configure failed: %d", status);
        cleanup();
        return {0, 0};
    }

    status = AMediaCodec_start(codec);
    if (status != AMEDIA_OK) {
        LOGE("AMediaCodec_start failed: %d", status);
        cleanup();
        return {0, 0};
    }
    codecStarted = true;

    // -------------------------------------------------------------------------
    // 3. Decode up to 30 s of audio → mono float32 samples.
    // -------------------------------------------------------------------------
    const size_t maxFrames = static_cast<size_t>(sampleRate) * static_cast<size_t>(kMaxSeconds);
    std::vector<float> monoSamples;
    monoSamples.reserve(maxFrames);

    bool inputEOS = false;

    while (monoSamples.size() < maxFrames) {
        // Feed input buffers.
        if (!inputEOS) {
            ssize_t inIdx = AMediaCodec_dequeueInputBuffer(codec, kCodecTimeoutUs);
            if (inIdx >= 0) {
                size_t bufSize = 0;
                uint8_t* buf = AMediaCodec_getInputBuffer(
                        codec, static_cast<size_t>(inIdx), &bufSize);
                if (buf) {
                    ssize_t sampleSize = AMediaExtractor_readSampleData(
                            extractor, buf, bufSize);
                    if (sampleSize < 0) {
                        AMediaCodec_queueInputBuffer(
                                codec, static_cast<size_t>(inIdx),
                                0, 0,
                                AMediaExtractor_getSampleTime(extractor),
                                AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                        inputEOS = true;
                    } else {
                        int64_t pts = AMediaExtractor_getSampleTime(extractor);
                        AMediaCodec_queueInputBuffer(
                                codec, static_cast<size_t>(inIdx),
                                0, static_cast<size_t>(sampleSize), pts, 0);
                        AMediaExtractor_advance(extractor);
                    }
                }
            }
        }

        // Drain output buffers.
        AMediaCodecBufferInfo info;
        ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(codec, &info, kCodecTimeoutUs);

        if (outIdx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            // Re-read format in case the codec reports updated params.
            AMediaFormat* fmt = AMediaCodec_getOutputFormat(codec);
            int32_t sr = 0, ch = 0, enc = -1;
            AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sr);
            AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &ch);
            AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_PCM_ENCODING, &enc);
            AMediaFormat_delete(fmt);
            if (sr > 0) sampleRate  = sr;
            if (ch > 0) channels    = ch;
            if (enc != -1) pcmEncoding = enc;
            continue;
        }

        if (outIdx < 0) {
            // No output yet.
            if (inputEOS) {
                // We've sent EOS and are waiting for the last output buffers.
                // Keep looping; eventually the codec will emit EOS output.
                continue;
            }
            continue;
        }

        size_t outBufSize = 0;
        const uint8_t* outBuf = AMediaCodec_getOutputBuffer(
                codec, static_cast<size_t>(outIdx), &outBufSize);

        bool isEOS = (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0;

        if (outBuf && info.size > 0) {
            appendMonoFrames(outBuf + info.offset,
                             static_cast<size_t>(info.size),
                             pcmEncoding, channels,
                             monoSamples, maxFrames);
        }

        AMediaCodec_releaseOutputBuffer(codec, static_cast<size_t>(outIdx), false);

        if (isEOS) {
            break;
        }
    }

    cleanup();

    // -------------------------------------------------------------------------
    // 4. Resample the decoded mono to the analysis rate, then run the validated
    //    DSP pipeline. bpm_dsp expects 44100 so the playground constants (STFT
    //    2048/512, 500/200 Hz band cutoffs, etc.) apply unchanged.
    // -------------------------------------------------------------------------
    std::vector<float> work;
    if (sampleRate == kWorkingRate) {
        work.swap(monoSamples);
    } else {
        const size_t inN  = monoSamples.size();
        const size_t outN = inN * static_cast<size_t>(kWorkingRate)
                          / static_cast<size_t>(sampleRate);
        work.resize(outN);
        const double ratio = static_cast<double>(sampleRate) / kWorkingRate;
        for (size_t i = 0; i < outN; ++i) {
            const double srcPos = static_cast<double>(i) * ratio;
            const size_t j      = static_cast<size_t>(srcPos);
            const float  a      = monoSamples[j];
            const float  b      = (j + 1 < inN) ? monoSamples[j + 1] : a;
            work[i] = a + static_cast<float>(srcPos - static_cast<double>(j)) * (b - a);
        }
    }

    const DspResult dsp = analyzeBpmDsp(work, kWorkingRate);
    if (!(dsp.bpm > 0.0)) {
        LOGD("DSP analysis found no tempo");
        return {0.0, 0, 4};
    }

    // Convert the first-beat offset from the 44100 analysis rate to the 48000 Hz
    // frame units used by AudioEngine.
    const int64_t firstBeatFrames =
            dsp.firstBeatFrames * 48000LL / static_cast<int64_t>(kWorkingRate);

    LOGD("Analysis complete: bpm=%.2f firstBeatFrames=%lld beatsPerBar=%d",
         dsp.bpm, static_cast<long long>(firstBeatFrames), dsp.beatsPerBar);
    return {dsp.bpm, firstBeatFrames, dsp.beatsPerBar};
}
