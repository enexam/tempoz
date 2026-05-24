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

    // ---- parameter setters ----

    var bpm: Int = 120
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
    external fun nativeSetBpm(handle: Long, bpm: Int)
    external fun nativeSetBeatsPerBar(handle: Long, beatsPerBar: Int)
    external fun nativeSetTrackVolume(handle: Long, volume: Float)
    external fun nativeSetClickVolume(handle: Long, volume: Float)
    external fun nativeDestroy(handle: Long)
}
