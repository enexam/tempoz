package com.example.tempoz

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.tempoz.data.AppSettings
import com.example.tempoz.data.SettingsRepository
import com.example.tempoz.data.TempozDatabase
import com.example.tempoz.data.TrackEntity
import com.example.tempoz.data.TrackRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.example.tempoz.quantizeBpm

/** Three-state playback machine exposed to the UI. */
enum class PlaybackState { STOPPED, PLAYING, PAUSED }

/**
 * Ordered list of synthesized click voice names. The index is the id passed to
 * the native engine (and stored in [AppSettings.clickSound]).
 * Must stay in sync with the voice switch in ClickGenerator.cpp.
 */
val ClickSoundOptions = listOf("Click", "Rim", "Wood block", "Beep", "Cowbell", "Hi-hat")

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
    private val settingsRepository = SettingsRepository(application)

    /** Global app settings, backed by DataStore. */
    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    init {
        engine.create()
        // Seed track/click volumes from persisted defaults (one-shot; reads the actual stored
        // value, not the stateIn initial). This must use .first() — not settings.value — because
        // the StateFlow's initial value is always the in-memory default until DataStore emits.
        viewModelScope.launch {
            val s = settingsRepository.settings.first()
            setTrackVolume(s.defaultTrackVolume)
            setClickVolume(s.defaultClickVolume)
            applyClickSoundToEngine(s.clickSound)
            engine.subdivision = s.subdivision
            engine.ghostVolume = s.ghostVolume
        }
        viewModelScope.launch {
            PlaybackController.actions.collect { action ->
                when (action) {
                    PlaybackController.ACTION_PLAY_PAUSE -> {
                        when (_playbackState.value) {
                            PlaybackState.PLAYING -> pause()
                            PlaybackState.PAUSED -> resume()
                            PlaybackState.STOPPED -> if (fileUri.value != null) play()
                        }
                    }
                    PlaybackController.ACTION_RESTART -> restart()
                }
            }
        }
    }

    val fileUri = MutableStateFlow<Uri?>(null)
    val fileName = MutableStateFlow("No file selected")
    val isAnalyzing = MutableStateFlow(false)
    val bpm = MutableStateFlow(120.0)
    val beatsPerBar = MutableStateFlow(4)
    val trackVolume = MutableStateFlow(1.0f)
    val clickVolume = MutableStateFlow(0.8f)

    /** First-beat offset of the loaded track, in 48 kHz frames (editable, persisted). */
    val beatOffsetFrames = MutableStateFlow(0L)

    private val _playbackState = MutableStateFlow(PlaybackState.STOPPED)
    val playbackState: StateFlow<PlaybackState> = _playbackState

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs

    /** Reactive list of all persisted tracks ordered by last used time. */
    val tracks: StateFlow<List<TrackEntity>> = repository.tracks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** URI of the most recently loaded file; identifies the current track for navigation/saves. */
    private var currentTrackUri: Uri? = null

    val loopMode = MutableStateFlow(false)

    /** Position + end-of-file polling loop (100 ms). Active only while PLAYING. */
    private var pollJob: Job? = null

    /** Recent tap timestamps (ns) for tap-tempo; cleared after a long gap. */
    private val tapTimes = ArrayDeque<Long>()

    /**
     * Opens [uri] via the content resolver, loads the file into the audio engine, and updates
     * [fileUri] and [fileName]. If the file length cannot be determined, the load is skipped.
     * Upserts a [TrackEntity] to persist the track with the current BPM and beats per bar.
     */
    fun selectFile(context: Context, uri: Uri) {
        val pfd = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (e: SecurityException) {
            // No persistable grant for this URI — e.g. a track imported before
            // persistable permissions were taken (old ACTION_GET_CONTENT). It can
            // never be reopened, so drop it from the library instead of crashing.
            viewModelScope.launch { repository.deleteByUri(uri.toString()) }
            return
        } catch (e: FileNotFoundException) {
            return
        } ?: return
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
        if (_playbackState.value != PlaybackState.STOPPED) {
            stop()
        }
        engine.loadFile(pfd.fd, 0L, length)
        pfd.close()
        _durationMs.value = engine.getDurationMs()
        _currentPositionMs.value = 0L
        fileUri.value = uri
        val name = displayName ?: uri.lastPathSegment ?: "Unknown"
        fileName.value = name
        currentTrackUri = uri
        val existingTrack = tracks.value.find { it.uri == uri.toString() }
        // Apply the saved first-beat offset on load so the engine and UI agree
        // before any (re)analysis, and a fresh import starts from 0 rather than
        // inheriting the previous track's offset.
        val savedOffset = existingTrack?.beatOffsetFrames ?: 0L
        beatOffsetFrames.value = savedOffset
        engine.setFirstBeatOffset(savedOffset)
        viewModelScope.launch {
            repository.upsert(
                TrackEntity(
                    uri = uri.toString(),
                    displayName = name,
                    bpm = bpm.value,
                    beatsPerBar = beatsPerBar.value,
                    lastUsedMs = System.currentTimeMillis(),
                    detectedBpm = existingTrack?.detectedBpm,
                    beatOffsetFrames = existingTrack?.beatOffsetFrames
                )
            )
        }
        val capturedLength = length
        val capturedUri = uri
        val alreadyAnalyzed = tracks.value.find { it.uri == uri.toString() }?.detectedBpm != null
        if (!alreadyAnalyzed) {
            viewModelScope.launch(Dispatchers.IO) {
                isAnalyzing.value = true
                val analysisPfd = try {
                    context.contentResolver.openFileDescriptor(capturedUri, "r")
                } catch (e: Exception) {
                    null
                }
                if (analysisPfd != null) {
                    val result = engine.analyzeBpm(analysisPfd.fd, 0L, capturedLength)
                    analysisPfd.close()
                    if (result[0] > 0.0 && currentTrackUri == capturedUri) {
                        val detectedBeatsPerBar = result[2].toInt().takeIf { it > 0 } ?: 4
                        engine.setFirstBeatOffset(result[1].toLong())
                        beatOffsetFrames.value = result[1].toLong()
                        setBpm(result[0])
                        setBeatsPerBar(detectedBeatsPerBar)
                        repository.upsert(
                            TrackEntity(
                                uri = capturedUri.toString(),
                                displayName = name,
                                bpm = result[0],
                                beatsPerBar = detectedBeatsPerBar,
                                lastUsedMs = System.currentTimeMillis(),
                                detectedBpm = result[0],
                                beatOffsetFrames = result[1].toLong()
                            )
                        )
                    }
                }
                isAnalyzing.value = false
            }
        }
    }

    /**
     * Starts playback from the current playhead. No-op if no file has been loaded or if the
     * engine is not STOPPED. Applies the current [bpm] and [beatsPerBar] before starting.
     */
    fun play() {
        if (fileUri.value == null) return
        if (_playbackState.value != PlaybackState.STOPPED) return
        engine.bpm = bpm.value
        engine.beatsPerBar = beatsPerBar.value
        engine.start()
        _playbackState.value = PlaybackState.PLAYING
        updateNotification()
        launchPollJob()
    }

    /** Pauses playback. No-op unless currently PLAYING. */
    fun pause() {
        if (_playbackState.value != PlaybackState.PLAYING) return
        engine.pause()
        cancelPollJob()
        _playbackState.value = PlaybackState.PAUSED
        updateNotification()
    }

    /** Resumes from a paused position. No-op unless currently PAUSED. */
    fun resume() {
        if (_playbackState.value != PlaybackState.PAUSED) return
        engine.resume()
        _playbackState.value = PlaybackState.PLAYING
        updateNotification()
        launchPollJob()
    }

    /**
     * Rewinds to the start. If playing, the engine rewinds in place (the stream stays open); if
     * paused or stopped, playback starts from the top. Works from any state with a file loaded.
     */
    fun restart() {
        if (fileUri.value == null) return
        engine.seekTo(0)
        _currentPositionMs.value = 0L
        if (_playbackState.value != PlaybackState.PLAYING) {
            engine.start()
            _playbackState.value = PlaybackState.PLAYING
            updateNotification()
            launchPollJob()
        }
    }

    /** Stops playback and resets state to STOPPED with the playhead at 0. */
    fun stop() {
        engine.stop()
        cancelPollJob()
        _playbackState.value = PlaybackState.STOPPED
        _currentPositionMs.value = 0L
        getApplication<Application>().startService(
            Intent(getApplication(), PlaybackService::class.java).apply {
                action = PlaybackService.ACTION_STOP
            }
        )
    }

    /** Updates [bpm] and propagates the new value to the engine immediately. */
    fun setBpm(value: Double) {
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

    /** Persists [value] as the default track volume for future sessions. */
    fun setDefaultTrackVolume(value: Float) {
        viewModelScope.launch { settingsRepository.setDefaultTrackVolume(value) }
    }

    /** Persists [value] as the default click volume for future sessions. */
    fun setDefaultClickVolume(value: Float) {
        viewModelScope.launch { settingsRepository.setDefaultClickVolume(value) }
    }

    /**
     * Sets the click voice by name (must be one of [ClickSoundOptions]), applies it
     * to the engine live, and persists it as the global default.
     */
    fun setClickSound(name: String) {
        applyClickSoundToEngine(name)
        viewModelScope.launch { settingsRepository.setClickSound(name) }
    }

    /** Applies the click sound to the engine without persisting. */
    private fun applyClickSoundToEngine(name: String) {
        val id = ClickSoundOptions.indexOf(name).coerceAtLeast(0)
        engine.clickSound = id
    }

    /**
     * Sets the subdivision (1=quarter/none, 2=eighth, 3=triplet, 4=sixteenth),
     * applies it to the engine live, and persists it.
     */
    fun setSubdivision(value: Int) {
        engine.subdivision = value
        viewModelScope.launch { settingsRepository.setSubdivision(value) }
    }

    /**
     * Sets the relative ghost (sub-beat) click volume in [0, 1],
     * applies it to the engine live, and persists it.
     */
    fun setGhostVolume(value: Float) {
        engine.ghostVolume = value
        viewModelScope.launch { settingsRepository.setGhostVolume(value) }
    }

    /** Seeks to [positionMs] and updates [currentPositionMs] immediately. */
    fun seekTo(positionMs: Long) {
        _currentPositionMs.value = positionMs
        engine.seekTo(positionMs)
    }

    /** Latency-compensated position of the frame currently being heard, for visual beat sync. */
    fun audiblePositionMs(): Long = engine.getAudiblePositionMs()

    /** Sets the first-beat offset (48 kHz frames), applying it to the engine live. */
    fun setBeatOffsetFrames(frames: Long) {
        val v = frames.coerceAtLeast(0L)
        beatOffsetFrames.value = v
        engine.setFirstBeatOffset(v)
    }

    /**
     * Registers a tap for tap-tempo: averages the interval over recent taps and sets [bpm].
     * A gap longer than ~2 s starts a fresh measurement.
     */
    fun tapTempo() {
        val now = System.nanoTime()
        if (tapTimes.isNotEmpty() && now - tapTimes.last() > 2_000_000_000L) {
            tapTimes.clear()
        }
        tapTimes.addLast(now)
        while (tapTimes.size > 6) tapTimes.removeFirst()
        if (tapTimes.size >= 2) {
            val intervalNs = (tapTimes.last() - tapTimes.first()).toDouble() / (tapTimes.size - 1)
            if (intervalNs > 0.0) {
                val tapped = 60_000_000_000.0 / intervalNs
                if (tapped in 20.0..400.0) {
                    setBpm(quantizeBpm(tapped))
                }
            }
        }
    }

    /** Toggles loop mode on/off. */
    fun toggleLoop() {
        loopMode.value = !loopMode.value
    }

    /**
     * Loads the track after the current one in [tracks] (circular). No-op if fewer than 2 tracks
     * or if [currentTrackUri] is not found in the list. Resumes playback if the engine was playing.
     */
    fun nextTrack() {
        val list = tracks.value
        if (list.size <= 1) return
        val idx = list.indexOfFirst { it.uri == currentTrackUri?.toString() }
        if (idx == -1) return
        val wasPlaying = _playbackState.value == PlaybackState.PLAYING
        selectTrack(list[(idx + 1) % list.size])
        if (wasPlaying) play()
    }

    /**
     * Loads the track before the current one in [tracks] (circular). No-op if fewer than 2 tracks
     * or if [currentTrackUri] is not found in the list. Resumes playback if the engine was playing.
     */
    fun prevTrack() {
        val list = tracks.value
        if (list.size <= 1) return
        val idx = list.indexOfFirst { it.uri == currentTrackUri?.toString() }
        if (idx == -1) return
        val wasPlaying = _playbackState.value == PlaybackState.PLAYING
        selectTrack(list[(idx - 1 + list.size) % list.size])
        if (wasPlaying) play()
    }

    /** Removes [track] from the persistent store. */
    fun deleteTrack(track: TrackEntity) {
        viewModelScope.launch { repository.deleteByUri(track.uri) }
    }

    /**
     * Loads [track] into the engine and updates BPM and beats per bar from the persisted values.
     * Also restores the detected beat offset so the click is aligned to the beat grid.
     */
    fun selectTrack(track: TrackEntity) {
        bpm.value = track.bpm
        beatsPerBar.value = track.beatsPerBar
        engine.bpm = track.bpm
        engine.beatsPerBar = track.beatsPerBar
        engine.setFirstBeatOffset(track.beatOffsetFrames ?: 0L)
        selectFile(getApplication(), Uri.parse(track.uri))
    }

    /**
     * Persists the current track's edited [bpm], [beatsPerBar], and [beatOffsetFrames] without
     * reloading audio, preserving the auto-detected BPM reference. No-op if no track is loaded or
     * it is not yet in the store. Backs the Track settings sheet's Save action.
     */
    fun saveCurrentTrackParams() {
        val uri = currentTrackUri ?: return
        val existing = tracks.value.find { it.uri == uri.toString() } ?: return
        viewModelScope.launch {
            repository.upsert(
                existing.copy(
                    bpm = bpm.value,
                    beatsPerBar = beatsPerBar.value,
                    beatOffsetFrames = beatOffsetFrames.value,
                    lastUsedMs = System.currentTimeMillis()
                )
            )
        }
    }

    override fun onCleared() {
        stop()
        engine.destroy()
    }

    // ---- internals ----

    private fun updateNotification() {
        val intent = Intent(getApplication(), PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_START
            putExtra("trackName", fileName.value)
            putExtra("isPlaying", _playbackState.value == PlaybackState.PLAYING)
        }
        getApplication<Application>().startForegroundService(intent)
    }

    private fun launchPollJob() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(100)
                _currentPositionMs.value = engine.getPositionMs()
                if (engine.isEnded()) {
                    if (loopMode.value && fileUri.value != null) {
                        // Seamless loop: the engine rewinds with the stream still open.
                        engine.seekTo(0)
                        _currentPositionMs.value = 0L
                    } else {
                        stop()
                        break
                    }
                }
            }
        }
    }

    private fun cancelPollJob() {
        pollJob?.cancel()
        pollJob = null
    }
}
