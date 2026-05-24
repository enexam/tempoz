package com.example.tempoz

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * ViewModel that owns the [AudioEngine] lifecycle and exposes playback state as [MutableStateFlow]s.
 *
 * Call order for playback: pick a file via [selectFile], then call [play] / [stop] as needed.
 * Volume and BPM changes propagate to the engine immediately regardless of playback state.
 */
class PlaybackViewModel : ViewModel() {

    private val engine = AudioEngine()

    init {
        engine.create()
    }

    val fileUri = MutableStateFlow<Uri?>(null)
    val fileName = MutableStateFlow("No file selected")
    val bpm = MutableStateFlow(120)
    val beatsPerBar = MutableStateFlow(4)
    val trackVolume = MutableStateFlow(1.0f)
    val clickVolume = MutableStateFlow(0.8f)
    val isPlaying = MutableStateFlow(false)

    /**
     * Opens [uri] via the content resolver, loads the file into the audio engine, and updates
     * [fileUri] and [fileName]. If the file length cannot be determined, the load is skipped.
     */
    fun selectFile(context: Context, uri: Uri) {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return
        var length = pfd.statSize
        var displayName: String? = null
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                displayName = cursor.getString(0)
                if (length <= 0L) {
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                        length = cursor.getLong(sizeIndex)
                    }
                }
            }
        }
        if (length <= 0L) {
            pfd.close()
            return
        }
        engine.loadFile(pfd.fd, 0L, length)
        pfd.close()
        fileUri.value = uri
        fileName.value = displayName ?: uri.lastPathSegment ?: "Unknown"
    }

    /**
     * Starts playback. No-op if no file has been loaded (i.e. [fileUri] is null).
     *
     * Applies the current [bpm] and [beatsPerBar] values before starting.
     */
    fun play() {
        if (fileUri.value == null) return
        engine.bpm = bpm.value
        engine.beatsPerBar = beatsPerBar.value
        engine.start()
        isPlaying.value = true
    }

    /** Stops playback. */
    fun stop() {
        engine.stop()
        isPlaying.value = false
    }

    /** Updates [bpm] and propagates the new value to the engine immediately. */
    fun setBpm(value: Int) {
        bpm.value = value
        engine.bpm = value
    }

    /** Updates [beatsPerBar] and propagates the new value to the engine immediately. */
    fun setBeatsPerBar(value: Int) {
        beatsPerBar.value = value
        engine.beatsPerBar = value
    }

    /** Updates [trackVolume] and propagates the new value to the engine immediately. */
    fun setTrackVolume(value: Float) {
        trackVolume.value = value
        engine.trackVolume = value
    }

    /** Updates [clickVolume] and propagates the new value to the engine immediately. */
    fun setClickVolume(value: Float) {
        clickVolume.value = value
        engine.clickVolume = value
    }

    override fun onCleared() {
        stop()
        engine.destroy()
    }
}
