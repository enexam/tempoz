#pragma once

#include <atomic>
#include <memory>
#include <string>
#include <thread>
#include <vector>

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>
#include <oboe/FifoBuffer.h>

/**
 * Decodes a compressed audio file via MediaCodec/MediaExtractor and feeds
 * float32 PCM into an oboe::FifoBuffer for lock-free consumption on the
 * audio thread.
 *
 * Thread model:
 *   - open() / start() / stop() / pause() / resume() are called on the main thread.
 *   - read() / isEOF() / getFramesConsumed() are called on the audio callback thread
 *     (no allocs).
 *   - An internal decode thread writes to the FifoBuffer.
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
     * @param fd               file descriptor (may be closed after open() returns)
     * @param offset           byte offset of the data source within fd
     * @param length           byte length of the data source
     * @param targetSampleRate desired output sample rate in Hz
     * @param targetChannels   desired output channel count (1 or 2)
     */
    bool open(int fd, int64_t offset, int64_t length,
              int targetSampleRate, int targetChannels);

    /** Launch the decode thread. Call after open(). */
    void start();

    /** Signal the decode thread to stop and block until it joins. */
    void stop();

    /**
     * Stop the decode thread and flush the FIFO to empty.
     * The codec remains open (Executing state). Call resume() to restart.
     */
    void pause();

    /**
     * Seek to frameOffset in the audio file, flush the codec and FIFO, and
     * restart the decode thread. If the codec is not currently running
     * (e.g. after stop()), it is started first.
     *
     * Resets mFramesConsumed to zero.
     *
     * @param frameOffset target position in output frames (at mTargetSampleRate)
     */
    void resume(uint64_t frameOffset);

    /** Seek back to the beginning of the file. Equivalent to resume(0). */
    void seekToStart();

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
     * open() or resume(). Safe to call from any thread.
     */
    uint64_t getFramesConsumed();

private:
    /** Decode loop executed by mDecodeThread. */
    void decodeLoop();

    /** Launch the decode thread without the mStarted guard. Must only be called
     *  when the codec is in Executing state and no thread is running. */
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

    // ---- decode thread ----
    std::thread           mDecodeThread;
    std::atomic<bool>     mStopRequested{false};
    std::atomic<bool>     mDecoderDone{false};  // codec EOS reached + flushed
    bool                  mStarted{false};       // true between start() and stop()

    // ---- playback position ----
    // Incremented by numFrames on every read() call. Reset on open()/resume().
    std::atomic<uint64_t> mFramesConsumed{0};

    // ---- scratch buffers (decode thread only) ----
    // Float32 samples after format conversion, before resampling/upmix.
    std::vector<float> mConvertBuf;
    // Float32 samples after resampling, before upmix write to fifo.
    std::vector<float> mResampleBuf;

    // ---- linear resampler state (decode thread only) ----
    // Last frame of input (per input channel) kept for interpolation across
    // codec buffer boundaries.
    std::vector<float> mLastFrame;
    // Sub-sample position in [0, 1) — fractional distance into the current
    // input interval.
    double mResamplePhase = 0.0;
};
