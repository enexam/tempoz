package com.example.tempoz

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.tempoz.data.TempozDatabase
import com.example.tempoz.data.TrackEntity
import com.example.tempoz.data.TrackRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Three-state playback machine exposed to the UI. */
enum class PlaybackState { STOPPED, PLAYING, PAUSED }

/**
 * ViewModel that owns the [AudioEngine] lifecycle and exposes playback state as [StateFlow]s.
 *
 * Call order for playback: pick a file via [selectFile], then call [play] / [pause] / [resume] /
 * [restart] / [stop] as needed. Volume and BPM changes propagate to the engine immediately
 * regardless of playback state.
 */
class PlaybackViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = AudioEngine()
    private val repository = TrackRepository(TempozDatabase.getInstance(application).trackDao())

    init {
        engine.create()
    }

    val fileUri = MutableStateFlow<Uri?>(null)
    val fileName = MutableStateFlow("No file selected")
    val bpm = MutableStateFlow(120)
    val beatsPerBar = MutableStateFlow(4)
    val trackVolume = MutableStateFlow(1.0f)
    val clickVolume = MutableStateFlow(0.8f)

    private val _playbackState = MutableStateFlow(PlaybackState.STOPPED)
    val playbackState: StateFlow<PlaybackState> = _playbackState

    /** Reactive list of all persisted tracks ordered by last used time. */
    val tracks: StateFlow<List<TrackEntity>> = repository.tracks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Tracks the URI of the most recently loaded file for upsert in [play]. */
    private var currentTrackUri: Uri? = null

    /** Nullable job for the 250 ms EOF-detection polling loop. Active only when PLAYING. */
    private var pollJob: Job? = null

    /**
     * Opens [uri] via the content resolver, loads the file into the audio engine, and updates
     * [fileUri] and [fileName]. If the file length cannot be determined, the load is skipped.
     * Upserts a [TrackEntity] to persist the track with the current BPM and beats per bar.
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
        val name = displayName ?: uri.lastPathSegment ?: "Unknown"
        fileName.value = name
        currentTrackUri = uri
        viewModelScope.launch {
            repository.upsert(
                TrackEntity(
                    uri = uri.toString(),
                    displayName = name,
                    bpm = bpm.value,
                    beatsPerBar = beatsPerBar.value,
                    lastUsedMs = System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * Starts playback from the current position. No-op if no file has been loaded or if the
     * engine is not STOPPED. Applies the current [bpm] and [beatsPerBar] before starting and
     * upserts the current track to update its BPM/beatsPerBar/lastUsedMs.
     */
    fun play() {
        if (fileUri.value == null) return
        if (_playbackState.value != PlaybackState.STOPPED) return
        val uri = currentTrackUri
        if (uri != null) {
            viewModelScope.launch {
                repository.upsert(
                    TrackEntity(
                        uri = uri.toString(),
                        displayName = fileName.value,
                        bpm = bpm.value,
                        beatsPerBar = beatsPerBar.value,
                        lastUsedMs = System.currentTimeMillis()
                    )
                )
            }
        }
        engine.bpm = bpm.value
        engine.beatsPerBar = beatsPerBar.value
        engine.start()
        _playbackState.value = PlaybackState.PLAYING
        launchPollJob()
    }

    /** Pauses playback. No-op unless currently PLAYING. */
    fun pause() {
        if (_playbackState.value != PlaybackState.PLAYING) return
        engine.pause()
        cancelPollJob()
        _playbackState.value = PlaybackState.PAUSED
    }

    /** Resumes from a paused position. No-op unless currently PAUSED. */
    fun resume() {
        if (_playbackState.value != PlaybackState.PAUSED) return
        engine.resume()
        _playbackState.value = PlaybackState.PLAYING
        launchPollJob()
    }

    /**
     * Stops the engine, seeks to the beginning, then starts playback from the top. Works from
     * any state as long as a file is loaded.
     */
    fun restart() {
        if (fileUri.value == null) return
        cancelPollJob()
        engine.stop()
        engine.seekToStart()
        _playbackState.value = PlaybackState.STOPPED
        play()
    }

    /** Stops playback and resets state to STOPPED. */
    fun stop() {
        engine.stop()
        cancelPollJob()
        _playbackState.value = PlaybackState.STOPPED
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

    /**
     * Loads [track] into the engine and updates BPM and beats per bar from the persisted values.
     */
    fun selectTrack(track: TrackEntity) {
        bpm.value = track.bpm
        beatsPerBar.value = track.beatsPerBar
        engine.bpm = track.bpm
        engine.beatsPerBar = track.beatsPerBar
        selectFile(getApplication(), Uri.parse(track.uri))
    }

    override fun onCleared() {
        stop()
        engine.destroy()
    }

    // ---- internals ----

    private fun launchPollJob() {
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(250)
                if (!engine.isPlaying()) {
                    _playbackState.value = PlaybackState.STOPPED
                    break
                }
            }
        }
    }

    private fun cancelPollJob() {
        pollJob?.cancel()
        pollJob = null
    }
}
