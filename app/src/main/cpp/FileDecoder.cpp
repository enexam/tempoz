#include "FileDecoder.h"

#include <android/log.h>
#include <media/NdkMediaFormat.h>
#include <string>
#include <thread>
#include <vector>
#include <cstring>
#include <cmath>

#define LOG_TAG "FileDecoder"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// MediaCodec timeout for dequeue calls (microseconds).
static constexpr int64_t kCodecTimeoutUs = 5000;  // 5 ms
// Sleep duration when the FIFO is full (ms).
static constexpr int kFifoFullSleepMs = 5;
// Initial scratch buffer capacity (frames).
static constexpr int kInitialScratchFrames = 4096;

// ---------------------------------------------------------------------------
// Construction / destruction
// ---------------------------------------------------------------------------

FileDecoder::FileDecoder() = default;

FileDecoder::~FileDecoder() {
    stop();
    if (mCodec) {
        AMediaCodec_delete(mCodec);
        mCodec = nullptr;
    }
    if (mExtractor) {
        AMediaExtractor_delete(mExtractor);
        mExtractor = nullptr;
    }
}

// ---------------------------------------------------------------------------
// open()
// ---------------------------------------------------------------------------

bool FileDecoder::open(int fd, int64_t offset, int64_t length,
                       int targetSampleRate, int targetChannels) {
    mTargetSampleRate = targetSampleRate;
    mTargetChannels   = targetChannels;

    // -- Create extractor --
    mExtractor = AMediaExtractor_new();
    media_status_t status = AMediaExtractor_setDataSourceFd(
            mExtractor, fd, static_cast<off64_t>(offset),
            static_cast<off64_t>(length));
    if (status != AMEDIA_OK) {
        LOGE("AMediaExtractor_setDataSourceFd failed: %d", status);
        return false;
    }

    // Find the first audio track.
    int numTracks = static_cast<int>(AMediaExtractor_getTrackCount(mExtractor));
    int audioTrack = -1;
    AMediaFormat* trackFormat = nullptr;

    for (int i = 0; i < numTracks; ++i) {
        AMediaFormat* fmt = AMediaExtractor_getTrackFormat(mExtractor, i);
        const char* mime = nullptr;
        AMediaFormat_getString(fmt, AMEDIAFORMAT_KEY_MIME, &mime);
        if (mime && strncmp(mime, "audio/", 6) == 0) {
            audioTrack = i;
            trackFormat = fmt;
            break;
        }
        AMediaFormat_delete(fmt);
    }

    if (audioTrack < 0) {
        LOGE("No audio track found");
        return false;
    }

    // Read format parameters.
    int32_t sampleRate = 0, channels = 0, pcmEncoding = -1;
    AMediaFormat_getInt32(trackFormat, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sampleRate);
    AMediaFormat_getInt32(trackFormat, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &channels);
    AMediaFormat_getInt32(trackFormat, AMEDIAFORMAT_KEY_PCM_ENCODING, &pcmEncoding);

    if (sampleRate <= 0 || channels <= 0) {
        LOGE("Invalid track format: sampleRate=%d channels=%d", sampleRate, channels);
        AMediaFormat_delete(trackFormat);
        return false;
    }

    mInputSampleRate = sampleRate;
    mInputChannels   = channels;
    mPcmEncoding     = pcmEncoding;
    mFramesConsumed.store(0, std::memory_order_relaxed);

    LOGD("Audio track: sampleRate=%d channels=%d pcmEncoding=%d",
         sampleRate, channels, pcmEncoding);

    // Select the track.
    AMediaExtractor_selectTrack(mExtractor, static_cast<size_t>(audioTrack));

    // -- Create decoder --
    // Copy mime before deleting the format object (getString returns a pointer
    // into the format's internal storage — accessing it after delete is UB).
    const char* mimeRaw = nullptr;
    AMediaFormat_getString(trackFormat, AMEDIAFORMAT_KEY_MIME, &mimeRaw);
    std::string mimeStr = mimeRaw ? mimeRaw : "";
    AMediaFormat_delete(trackFormat);

    mCodec = AMediaCodec_createDecoderByType(mimeStr.c_str());
    if (!mCodec) {
        LOGE("AMediaCodec_createDecoderByType failed for mime: %s", mimeStr.c_str());
        return false;
    }

    // Retrieve format again for codec configuration (needs fresh format object).
    AMediaFormat* codecFormat = AMediaExtractor_getTrackFormat(mExtractor,
                                                                static_cast<size_t>(audioTrack));
    status = AMediaCodec_configure(mCodec, codecFormat, nullptr, nullptr, 0);
    AMediaFormat_delete(codecFormat);

    if (status != AMEDIA_OK) {
        LOGE("AMediaCodec_configure failed: %d", status);
        return false;
    }

    // -- Create ring buffer: 2 seconds at target rate --
    uint32_t capacityFrames = static_cast<uint32_t>(targetSampleRate) * 2;
    uint32_t bytesPerFrame  = static_cast<uint32_t>(targetChannels) * sizeof(float);
    mFifo = std::make_unique<oboe::FifoBuffer>(bytesPerFrame, capacityFrames);

    // -- Allocate scratch buffers --
    mConvertBuf.resize(kInitialScratchFrames * static_cast<size_t>(mInputChannels));
    mResampleBuf.resize(kInitialScratchFrames * static_cast<size_t>(mTargetChannels));
    mLastFrame.assign(static_cast<size_t>(mInputChannels), 0.0f);
    mResamplePhase = 0.0;

    return true;
}

// ---------------------------------------------------------------------------
// start() / stop()
// ---------------------------------------------------------------------------

void FileDecoder::start() {
    if (!mCodec || mStarted) return;
    mStopRequested.store(false, std::memory_order_relaxed);
    mDecoderDone.store(false, std::memory_order_relaxed);
    media_status_t status = AMediaCodec_start(mCodec);
    if (status != AMEDIA_OK) {
        LOGE("AMediaCodec_start failed: %d", status);
        return;
    }
    mStarted = true;
    launchDecodeThread();
}

void FileDecoder::launchDecodeThread() {
    mDecodeThread = std::thread(&FileDecoder::decodeLoop, this);
}

void FileDecoder::stop() {
    mStopRequested.store(true, std::memory_order_relaxed);
    if (mDecodeThread.joinable()) {
        mDecodeThread.join();
    }
    if (mCodec && mStarted) {
        AMediaCodec_stop(mCodec);
        mStarted = false;
    }
}

void FileDecoder::pause() {
    // Signal and join the decode thread, then empty the FIFO.
    mStopRequested.store(true, std::memory_order_relaxed);
    if (mDecodeThread.joinable()) {
        mDecodeThread.join();
    }
    // Flush FIFO by aligning read counter to write counter.
    if (mFifo) {
        mFifo->setReadCounter(mFifo->getWriteCounter());
    }
}

void FileDecoder::resume(uint64_t frameOffset) {
    // Convert frame offset to microseconds for the extractor seek.
    int64_t seekUs = static_cast<int64_t>(
            static_cast<double>(frameOffset) * 1e6 / static_cast<double>(mTargetSampleRate));
    AMediaExtractor_seekTo(mExtractor, seekUs, AMEDIAEXTRACTOR_SEEK_PREVIOUS_SYNC);

    // Flush/reset codec. If codec is not yet running (e.g. after stop()), start it first.
    if (!mStarted) {
        media_status_t status = AMediaCodec_start(mCodec);
        if (status != AMEDIA_OK) {
            LOGE("AMediaCodec_start in resume() failed: %d", status);
            return;
        }
        mStarted = true;
    } else {
        AMediaCodec_flush(mCodec);
    }

    // Clear the FIFO.
    if (mFifo) {
        mFifo->setReadCounter(mFifo->getWriteCounter());
    }

    // Reset decode state.
    mFramesConsumed.store(0, std::memory_order_relaxed);
    mDecoderDone.store(false, std::memory_order_relaxed);
    mResamplePhase = 0.0;
    std::fill(mLastFrame.begin(), mLastFrame.end(), 0.0f);

    // Restart the decode thread.
    mStopRequested.store(false, std::memory_order_relaxed);
    launchDecodeThread();
}

void FileDecoder::seekToStart() {
    resume(0);
}

uint64_t FileDecoder::getFramesConsumed() {
    return mFramesConsumed.load(std::memory_order_relaxed);
}

// ---------------------------------------------------------------------------
// read() — audio thread
// ---------------------------------------------------------------------------

void FileDecoder::read(float* out, int numFrames) {
    int32_t got = mFifo->read(out, numFrames);
    if (got < numFrames) {
        // Starved — fill remainder with silence.
        int remaining = numFrames - got;
        memset(out + static_cast<ptrdiff_t>(got * mTargetChannels),
               0,
               static_cast<size_t>(remaining * mTargetChannels) * sizeof(float));
    }
    // Track real-time playback position (numFrames, not got, to include silence).
    mFramesConsumed.fetch_add(static_cast<uint64_t>(numFrames), std::memory_order_relaxed);
}

// ---------------------------------------------------------------------------
// isEOF() — audio thread
// ---------------------------------------------------------------------------

bool FileDecoder::isEOF() {
    return mDecoderDone.load(std::memory_order_acquire)
           && mFifo->getFullFramesAvailable() == 0;
}

// ---------------------------------------------------------------------------
// convertToFloat() — decode thread helper
// ---------------------------------------------------------------------------

/**
 * Convert raw PCM bytes from MediaCodec output to float32 interleaved.
 * Writes into mConvertBuf (resized as needed).
 * Returns the number of frames written.
 */
int FileDecoder::convertToFloat(const uint8_t* data, size_t byteSize,
                                int pcmEncoding, int inputChannels) {
    // ENCODING_PCM_16BIT = 2, ENCODING_PCM_FLOAT = 4, ENCODING_PCM_32BIT = 22
    // When pcmEncoding is -1 (key absent), assume int16.
    const int enc = (pcmEncoding == -1) ? 2 : pcmEncoding;

    int numFrames = 0;

    if (enc == 4 /* float */) {
        numFrames = static_cast<int>(byteSize / (sizeof(float) * static_cast<size_t>(inputChannels)));
        size_t needed = static_cast<size_t>(numFrames * inputChannels);
        if (mConvertBuf.size() < needed) mConvertBuf.resize(needed);
        memcpy(mConvertBuf.data(), data, byteSize);
    } else if (enc == 22 /* int32 */) {
        numFrames = static_cast<int>(byteSize / (sizeof(int32_t) * static_cast<size_t>(inputChannels)));
        size_t needed = static_cast<size_t>(numFrames * inputChannels);
        if (mConvertBuf.size() < needed) mConvertBuf.resize(needed);
        const auto* src = reinterpret_cast<const int32_t*>(data);
        constexpr float kScale = 1.0f / 2147483648.0f;
        for (int i = 0; i < numFrames * inputChannels; ++i) {
            mConvertBuf[i] = static_cast<float>(src[i]) * kScale;
        }
    } else {
        // Default: int16
        numFrames = static_cast<int>(byteSize / (sizeof(int16_t) * static_cast<size_t>(inputChannels)));
        size_t needed = static_cast<size_t>(numFrames * inputChannels);
        if (mConvertBuf.size() < needed) mConvertBuf.resize(needed);
        const auto* src = reinterpret_cast<const int16_t*>(data);
        constexpr float kScale = 1.0f / 32768.0f;
        for (int i = 0; i < numFrames * inputChannels; ++i) {
            mConvertBuf[i] = static_cast<float>(src[i]) * kScale;
        }
    }
    return numFrames;
}

// ---------------------------------------------------------------------------
// decodeLoop() — decode thread
// ---------------------------------------------------------------------------

/**
 * Linear resampler: given [inFrames] input frames (inputChannels interleaved)
 * in mConvertBuf, produce resampled output in mResampleBuf (targetChannels
 * interleaved) and write to mFifo.
 *
 * mResamplePhase and mLastFrame carry state across calls.
 */
void FileDecoder::decodeLoop() {
    const int outCh = mTargetChannels;
    bool inputEOS = false;

    while (!mStopRequested.load(std::memory_order_relaxed)) {
        // Recompute ratio and inCh each iteration so they reflect any
        // format change reported by AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED.
        const double ratio = static_cast<double>(mInputSampleRate)
                             / static_cast<double>(mTargetSampleRate);
        const int inCh = mInputChannels;

        // ---- Feed input buffers ----
        if (!inputEOS) {
            ssize_t inIdx = AMediaCodec_dequeueInputBuffer(mCodec, kCodecTimeoutUs);
            if (inIdx >= 0) {
                size_t bufSize = 0;
                uint8_t* buf = AMediaCodec_getInputBuffer(
                        mCodec, static_cast<size_t>(inIdx), &bufSize);
                if (buf) {
                    ssize_t sampleSize = AMediaExtractor_readSampleData(
                            mExtractor, buf, bufSize);
                    if (sampleSize < 0) {
                        // End of stream — signal codec.
                        AMediaCodec_queueInputBuffer(
                                mCodec, static_cast<size_t>(inIdx),
                                0, 0,
                                AMediaExtractor_getSampleTime(mExtractor),
                                AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                        inputEOS = true;
                    } else {
                        int64_t pts = AMediaExtractor_getSampleTime(mExtractor);
                        AMediaCodec_queueInputBuffer(
                                mCodec, static_cast<size_t>(inIdx),
                                0, static_cast<size_t>(sampleSize), pts, 0);
                        AMediaExtractor_advance(mExtractor);
                    }
                }
            }
        }

        // ---- Drain output buffers ----
        AMediaCodecBufferInfo info;
        ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(mCodec, &info, kCodecTimeoutUs);

        if (outIdx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            // Re-read format in case the codec reports updated params.
            AMediaFormat* fmt = AMediaCodec_getOutputFormat(mCodec);
            int32_t sr = 0, ch = 0, enc = -1;
            AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sr);
            AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &ch);
            AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_PCM_ENCODING, &enc);
            AMediaFormat_delete(fmt);
            if (sr > 0)  mInputSampleRate = sr;
            if (ch > 0)  mInputChannels   = ch;
            if (enc != -1) mPcmEncoding   = enc;
            continue;
        }

        if (outIdx < 0) {
            // No output yet — continue to try feeding input.
            continue;
        }

        // We have an output buffer.
        size_t outBufSize = 0;
        const uint8_t* outBuf = AMediaCodec_getOutputBuffer(
                mCodec, static_cast<size_t>(outIdx), &outBufSize);

        bool isEOS = (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0;

        if (outBuf && info.size > 0) {
            // 1. Convert to float32 (mConvertBuf, inCh interleaved).
            int inFrames = convertToFloat(outBuf + info.offset,
                                          static_cast<size_t>(info.size),
                                          mPcmEncoding, inCh);

            // 2. Resample (if needed) and upmix, then write to FIFO.
            //    We process in chunks that fit mResampleBuf.
            //    mResamplePhase tracks the fractional input position.

            if (mInputSampleRate == mTargetSampleRate) {
                // No resampling needed — just upmix if required.
                if (inCh == outCh) {
                    // Direct write in chunks (FIFO may be full, back-pressure).
                    int written = 0;
                    while (written < inFrames
                           && !mStopRequested.load(std::memory_order_relaxed)) {
                        int toWrite = inFrames - written;
                        int32_t n = mFifo->write(
                                mConvertBuf.data() + written * inCh, toWrite);
                        written += n;
                        if (n == 0) {
                            std::this_thread::sleep_for(
                                    std::chrono::milliseconds(kFifoFullSleepMs));
                        }
                    }
                } else {
                    // Mono → stereo upmix.
                    int written = 0;
                    while (written < inFrames
                           && !mStopRequested.load(std::memory_order_relaxed)) {
                        int batch = std::min(inFrames - written, kInitialScratchFrames);
                        size_t needed = static_cast<size_t>(batch * outCh);
                        if (mResampleBuf.size() < needed) mResampleBuf.resize(needed);
                        for (int f = 0; f < batch; ++f) {
                            float s = mConvertBuf[static_cast<size_t>((written + f) * inCh)];
                            for (int c = 0; c < outCh; ++c) {
                                mResampleBuf[static_cast<size_t>(f * outCh + c)] = s;
                            }
                        }
                        int32_t n = mFifo->write(mResampleBuf.data(), batch);
                        written += n;
                        if (n == 0) {
                            std::this_thread::sleep_for(
                                    std::chrono::milliseconds(kFifoFullSleepMs));
                        }
                    }
                }
            } else {
                // Linear resampler.
                // For each output frame we need input position p = phase.
                // We hold mLastFrame (previous input frame) and use it when
                // phase < 1 (interpolating between last frame and frame 0 of
                // current buffer).
                //
                // Input positions available: [-1 (mLastFrame), 0, 1, …, inFrames-1]
                // phase is the fractional input index of the *next* output frame.

                int outFrameCount = 0;
                // Initialise to mResamplePhase - 1.0 so that when mResamplePhase
                // carries a fractional remainder from the previous buffer (< 1.0),
                // idx0 = floor(phase) = -1 and the loop correctly interpolates
                // between mLastFrame and buf[0] for the first output sample.
                double phase = mResamplePhase - 1.0;

                // Estimate output frames for this chunk.
                int estimatedOut = static_cast<int>(
                        std::ceil(static_cast<double>(inFrames) / ratio)) + 2;
                size_t needed = static_cast<size_t>(estimatedOut * outCh);
                if (mResampleBuf.size() < needed) mResampleBuf.resize(needed);

                while (phase < static_cast<double>(inFrames)) {
                    if (mStopRequested.load(std::memory_order_relaxed)) break;

                    int idx0 = static_cast<int>(std::floor(phase));
                    int idx1 = idx0 + 1;
                    double frac = phase - static_cast<double>(idx0);

                    // Ensure resample buf has capacity for max(inCh, outCh) samples
                    // at writePos — the inner loop indexes up to writePos + inCh - 1.
                    size_t writePos = static_cast<size_t>(outFrameCount * outCh);
                    size_t needed = writePos + static_cast<size_t>(std::max(inCh, outCh));
                    if (needed > mResampleBuf.size()) {
                        mResampleBuf.resize(mResampleBuf.size() * 2 + needed);
                    }

                    const int copyChannels = std::min(inCh, outCh);
                    for (int c = 0; c < copyChannels; ++c) {
                        float s0 = (idx0 < 0)
                                   ? mLastFrame[static_cast<size_t>(c)]
                                   : mConvertBuf[static_cast<size_t>(idx0 * inCh + c)];
                        float s1 = (idx1 < inFrames)
                                   ? mConvertBuf[static_cast<size_t>(idx1 * inCh + c)]
                                   : mConvertBuf[static_cast<size_t>((inFrames - 1) * inCh + c)];
                        float interp = s0 + static_cast<float>(frac) * (s1 - s0);
                        mResampleBuf[writePos + static_cast<size_t>(c)] = interp;
                    }

                    // Fill extra output channels beyond inCh.
                    if (outCh > inCh) {
                        if (inCh == 1) {
                            // Mono → stereo: duplicate channel 0 into all extra channels.
                            float mono = mResampleBuf[writePos];
                            for (int c = 1; c < outCh; ++c) {
                                mResampleBuf[writePos + static_cast<size_t>(c)] = mono;
                            }
                        } else {
                            // Zero-fill any extra output channels.
                            for (int c = inCh; c < outCh; ++c) {
                                mResampleBuf[writePos + static_cast<size_t>(c)] = 0.0f;
                            }
                        }
                    }

                    ++outFrameCount;
                    phase += ratio;

                    // Flush in batches to avoid unbounded buffering.
                    if (outFrameCount >= kInitialScratchFrames) {
                        int written = 0;
                        while (written < outFrameCount
                               && !mStopRequested.load(std::memory_order_relaxed)) {
                            int32_t n = mFifo->write(
                                    mResampleBuf.data() + written * outCh,
                                    outFrameCount - written);
                            written += n;
                            if (n == 0) {
                                std::this_thread::sleep_for(
                                        std::chrono::milliseconds(kFifoFullSleepMs));
                            }
                        }
                        outFrameCount = 0;
                        // Shift resample buf write pointer back to 0.
                    }
                }

                // Save last input frame for next buffer's interpolation.
                if (inFrames > 0) {
                    for (int c = 0; c < inCh; ++c) {
                        mLastFrame[static_cast<size_t>(c)] =
                                mConvertBuf[static_cast<size_t>((inFrames - 1) * inCh + c)];
                    }
                }

                // Advance phase: subtract inFrames to get position relative to
                // the start of the *next* buffer.
                mResamplePhase = phase - static_cast<double>(inFrames);

                // Write any remaining resampled frames.
                if (outFrameCount > 0) {
                    int written = 0;
                    while (written < outFrameCount
                           && !mStopRequested.load(std::memory_order_relaxed)) {
                        int32_t n = mFifo->write(
                                mResampleBuf.data() + written * outCh,
                                outFrameCount - written);
                        written += n;
                        if (n == 0) {
                            std::this_thread::sleep_for(
                                    std::chrono::milliseconds(kFifoFullSleepMs));
                        }
                    }
                }
            }
        }

        AMediaCodec_releaseOutputBuffer(mCodec, static_cast<size_t>(outIdx), false);

        if (isEOS) {
            // All decoded data has been pushed to the FIFO.
            mDecoderDone.store(true, std::memory_order_release);
            break;
        }
    }
}
