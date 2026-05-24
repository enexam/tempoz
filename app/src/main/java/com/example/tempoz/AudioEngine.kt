package com.example.tempoz

/**
 * Kotlin JNI bridge to the native audio engine.
 *
 * All functions delegate to the C++ [AudioEngine] via a native handle obtained from [nativeCreate].
 * The caller is responsible for calling [nativeDestroy] when the engine is no longer needed.
 */
class AudioEngine {

    companion object {
        init {
            System.loadLibrary("tempoz")
        }
    }

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
