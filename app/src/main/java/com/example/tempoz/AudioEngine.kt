package com.example.tempoz

/**
 * Kotlin JNI bridge to the native audio engine.
 *
 * Call [create] before using any other method. Call [destroy] when done.
 * The native handle is stored internally; callers do not manage it directly.
 */
class AudioEngine {

    companion object {
        init {
            System.loadLibrary("tempoz")
        }
    }

    private var handle: Long = 0L

    // ---- lifecycle ----

    /** Allocates the native AudioEngine and stores the handle. */
    fun create() {
        handle = nativeCreate()
    }

    /** Frees the native AudioEngine. */
    fun destroy() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    // ---- playback control ----

    fun loadFile(fd: Int, offset: Long, length: Long) {
        nativeLoadFile(handle, fd, offset, length)
    }

    fun start() {
        nativeStart(handle)
    }

    fun stop() {
        nativeStop(handle)
    }

    fun pause() {
        nativePause(handle)
    }

    fun resume() {
        nativeResume(handle)
    }

    fun seekToStart() {
        nativeSeekToStart(handle)
    }

    fun isPlaying(): Boolean {
        return nativeIsPlaying(handle)
    }

    fun getDurationMs(): Long {
        return nativeGetDurationMs(handle)
    }

    fun getPositionMs(): Long {
        return nativeGetPositionMs(handle)
    }

    fun seekTo(positionMs: Long) {
        nativeSeekTo(handle, positionMs)
    }

    /**
     * Analyze the audio file identified by [fd, offset, length] and return
     * a three-element array [detectedBpm (fractional), firstBeatFrames, beatsPerBar].
     * Returns [0, 0, 0] if the engine has not been created or analysis fails.
     */
    fun analyzeBpm(fd: Int, offset: Long, length: Long): DoubleArray {
        if (handle == 0L) return DoubleArray(3)
        return nativeAnalyzeBpm(handle, fd, offset, length)
    }

    fun setFirstBeatOffset(frames: Long) {
        nativeSetFirstBeatOffset(handle, frames)
    }

    // ---- parameter setters ----

    var bpm: Double = 120.0
        set(value) {
            field = value
            nativeSetBpm(handle, value)
        }

    var beatsPerBar: Int = 4
        set(value) {
            field = value
            nativeSetBeatsPerBar(handle, value)
        }

    var trackVolume: Float = 1.0f
        set(value) {
            field = value
            nativeSetTrackVolume(handle, value)
        }

    var clickVolume: Float = 1.0f
        set(value) {
            field = value
            nativeSetClickVolume(handle, value)
        }

    // ---- raw JNI declarations ----

    external fun nativeCreate(): Long
    external fun nativeLoadFile(handle: Long, fd: Int, offset: Long, length: Long)
    external fun nativeStart(handle: Long)
    external fun nativeStop(handle: Long)
    external fun nativePause(handle: Long)
    external fun nativeResume(handle: Long)
    external fun nativeSeekToStart(handle: Long)
    external fun nativeIsPlaying(handle: Long): Boolean
    external fun nativeGetDurationMs(handle: Long): Long
    external fun nativeGetPositionMs(handle: Long): Long
    external fun nativeSeekTo(handle: Long, positionMs: Long)
    external fun nativeAnalyzeBpm(handle: Long, fd: Int, offset: Long, length: Long): DoubleArray
    external fun nativeSetFirstBeatOffset(handle: Long, frames: Long)
    external fun nativeSetBpm(handle: Long, bpm: Double)
    external fun nativeSetBeatsPerBar(handle: Long, beatsPerBar: Int)
    external fun nativeSetTrackVolume(handle: Long, volume: Float)
    external fun nativeSetClickVolume(handle: Long, volume: Float)
    external fun nativeDestroy(handle: Long)
}
