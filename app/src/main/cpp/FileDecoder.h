#pragma once

#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>
#include <oboe/FifoBuffer.h>

extern "C" {
#include "sonic.h"
}

/**
 * Decodes a compressed audio file via MediaCodec/MediaExtractor and feeds
 * float32 PCM into an oboe::FifoBuffer for lock-free consumption on the
 * audio thread.
 *
 * Lifecycle is built from two primitives that have one hard invariant: a decode
 * thread is never launched over a still-joinable thread.
 *   - startAt(frame) — (re)start decoding from a frame position. Always stops
 *     and joins any running decode thread first, so it doubles as the seek
 *     primitive.
 *   - stop() — stop and join the decode thread and halt the codec.
 *
 * Thread model:
 *   - open() / startAt() / stop() are called on the main thread only, serialized
 *     by the owner (AudioEngine, driven by the ViewModel). They are never called
 *     concurrently with each other.
 *   - read() / isEOF() / getFramesConsumed() are called on the audio callback
 *     thread (no allocations).
 *   - The internal decode thread is the sole producer into the FifoBuffer.
 *   - startAt()/open() mutate the FifoBuffer read/write counters; the owner must
 *     guarantee the audio callback is NOT running at that time (the engine stops
 *     the Oboe stream before any reseed). This keeps the FIFO strictly
 *     single-producer / single-consumer.
 *
 * Fd lifetime: AMediaExtractor dups the file descriptor internally, so the
 * caller may close the fd immediately after open() returns.
 */
class FileDecoder {
public:
    FileDecoder();
    ~FileDecoder();

    /**
     * Open the audio track identified by [fd, offset, length].
     * Sets up AMediaExtractor + AMediaCodec for the first audio track.
     * Returns true on success.
     *
     * Reentrant: releases any previously opened file (joins the decode thread,
     * deletes the prior codec/extractor) before opening the new one, so it is
     * safe to call on every track switch without leaking codec instances.
     *
     * @param fd               file descriptor (may be closed after open() returns)
     * @param offset           byte offset of the data source within fd
     * @param length           byte length of the data source
     * @param targetSampleRate desired output sample rate in Hz
     * @param targetChannels   desired output channel count (1 or 2)
     */
    bool open(int fd, int64_t offset, int64_t length,
              int targetSampleRate, int targetChannels);

    /**
     * Start (or restart) decoding so that the next frames read() returns begin
     * at [frameOffset]. Stops and joins any running decode thread, seeks the
     * extractor, flushes/starts the codec, clears the FIFO, resets decode state,
     * and launches a fresh decode thread.
     *
     * Also the seek primitive: call it with any frame position. Resets
     * mFramesConsumed to zero.
     *
     * frameOffset is in OUTPUT frames (after time-stretch). The extractor is
     * seeked to the corresponding SOURCE position: sourceFrame = frameOffset * s.
     */
    void startAt(uint64_t frameOffset);

    /**
     * Set the time-stretch speed factor in [0.5, 1.5].
     * At speed == 1.0 Sonic is bypassed entirely (byte-equivalent to old behavior).
     * Safe to call from any thread; the decode thread reads it atomically.
     */
    void setSpeed(float speed);

    /** Signal the decode thread to stop, join it, and halt the codec. */
    void stop();

    /**
     * Called from the audio thread. Drains up to numFrames frames from the
     * ring buffer into [out] (interleaved float32). Writes silence for any
     * frames that cannot be satisfied.
     *
     * Increments mFramesConsumed by numFrames (including any silence-filled
     * frames), reflecting real-time playback position.
     *
     * @param out       destination buffer, numFrames * targetChannels floats
     * @param numFrames number of audio frames requested
     */
    void read(float* out, int numFrames);

    /**
     * Returns true once the decoder has flushed all output and the ring
     * buffer is empty — i.e. all samples have been consumed.
     */
    bool isEOF();

    /**
     * Returns the total number of frames consumed via read() since the last
     * open() or startAt(). Safe to call from any thread.
     */
    uint64_t getFramesConsumed();

    /**
     * Returns the total duration of the opened audio file in frames at
     * mTargetSampleRate. Returns 0 if open() has not been called or the
     * format did not contain a duration.
     */
    int64_t getDurationFrames();

private:
    /** Decode loop executed by mDecodeThread. */
    void decodeLoop();

    /** Launch the decode thread. Must only be called when the codec is in
     *  Executing state and no decode thread is running (joined). */
    void launchDecodeThread();

    /**
     * Convert a raw MediaCodec output buffer to float32 interleaved samples
     * in mConvertBuf. Returns the number of float frames written.
     *
     * @param data     pointer to the raw codec output data
     * @param byteSize size of the raw data in bytes
     * @param pcmEncoding  AMEDIAFORMAT_KEY_PCM_ENCODING value (or -1 if absent → int16)
     */
    int convertToFloat(const uint8_t* data, size_t byteSize, int pcmEncoding,
                       int inputChannels);

    // ---- media objects ----
    AMediaExtractor* mExtractor = nullptr;
    AMediaCodec*     mCodec     = nullptr;

    // ---- format params discovered in open() ----
    int mInputSampleRate  = 0;
    int mInputChannels    = 0;
    int mTargetSampleRate = 0;
    int mTargetChannels   = 0;
    int mPcmEncoding      = -1; // AMEDIAFORMAT_KEY_PCM_ENCODING or -1

    // ---- ring buffer ----
    std::unique_ptr<oboe::FifoBuffer> mFifo;
    // Guards the FIFO counter flush in startAt() against an in-flight read() on
    // the audio thread. read() only ever try_locks (never blocks the audio
    // thread); startAt() holds it solely around the microsecond counter write.
    std::mutex mFifoMutex;

    // ---- decode thread ----
    std::thread           mDecodeThread;
    std::atomic<bool>     mStopRequested{false};
    std::atomic<bool>     mDecoderDone{false};  // codec EOS reached + flushed
    bool                  mStarted{false};       // codec is in Executing state

    // ---- playback position ----
    // Incremented by numFrames on every read() call. Reset on open()/startAt().
    std::atomic<uint64_t> mFramesConsumed{0};

    // Total duration in output frames; set in open() from AMEDIAFORMAT_KEY_DURATION.
    int64_t mDurationFrames{0};

    // ---- scratch buffers (decode thread only) ----
    // Float32 samples after format conversion, before resampling/upmix.
    std::vector<float> mConvertBuf;
    // Float32 samples after resampling, before upmix write to fifo.
    std::vector<float> mResampleBuf;
    // Float32 samples drained from Sonic before FIFO write.
    std::vector<float> mSonicDrainBuf;

    // ---- linear resampler state (decode thread only) ----
    // Last frame of input (per input channel) kept for interpolation across
    // codec buffer boundaries.
    std::vector<float> mLastFrame;
    // Sub-sample position in [0, 1) — fractional distance into the current
    // input interval.
    double mResamplePhase = 0.0;

    // ---- time-stretch (Sonic) ----
    // Speed factor applied to the 48 kHz stereo stream before the FIFO.
    // At 1.0 Sonic is bypassed (byte-equivalent). Written by setSpeed() from
    // any thread; read by the decode thread each iteration.
    std::atomic<float> mSpeed{1.0f};

    // Sonic stream: created / recreated in startAt() on the decode-thread side
    // (protected by the join barrier in startAt); destroyed in open() and ~.
    // Null when speed == 1.0 or before the first startAt call.
    sonicStream mSonicStream{nullptr};

    /**
     * Route stereo 48 kHz frames through Sonic (if speed != 1.0) then into
     * the FIFO. Blocks (with sleep) while the FIFO is full. Respects
     * mStopRequested. Decode thread only.
     *
     * @param frames   interleaved stereo float32 source pointer
     * @param numFrames number of frames (each frame = 2 floats for stereo)
     */
    void emitFrames(const float* frames, int numFrames);
};
