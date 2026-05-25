# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Tempoz

Tempoz is an Android app that adds a metronome to music tracks: simultaneous playback of an audio file and a click track, with BPM control, automatic BPM detection and beat synchronization, seek timeline, track navigation, loop mode, and volume mixing.

---

## Build & Test

All commands use the Gradle wrapper from the project root.

```
.\gradlew assembleDebug          # build debug APK
.\gradlew installDebug           # build and install on connected device/emulator
.\gradlew test                   # run unit tests
.\gradlew connectedAndroidTest   # run instrumented tests (requires device/emulator)
.\gradlew lint                   # run lint checks
.\gradlew :app:testDebugUnitTest --tests "com.example.tempoz.ExampleUnitTest.addition_isCorrect"  # run a single unit test
```

## Architecture

Single-module Android app (`app/`), Kotlin + Jetpack Compose, single `Activity` (`MainActivity`). No multi-module structure yet.

### Kotlin / UI Layer

- **`MainActivity`** — sole entry point; hosts the Compose UI tree via `setContent` + `PlayerScreen` composable. Requests `POST_NOTIFICATIONS` permission at runtime on API 33+.
- **`PlaybackViewModel`** — extends `AndroidViewModel`; owns the native `AudioEngine` instance and a `TrackRepository` for persistence. Exposes reactive state via `StateFlow`: `playbackState` (enum: `STOPPED`, `PLAYING`, `PAUSED`), `bpm`, `beatsPerBar`, `trackVolume`, `clickVolume`, `fileUri`, `durationMs`, `currentPositionMs`, `loopMode`, `isAnalyzing`, and `tracks` (all imported tracks). Methods: `selectFile(context, uri)` (upserts track in DB, triggers BPM analysis in IO coroutine), `selectTrack(track)` (loads saved BPM/beatsPerBar/beatOffset), `play()`, `pause()`, `resume()`, `restart()`, `stop()`, `seekTo(positionMs)`, `nextTrack()`, `prevTrack()`, `toggleLoop()`, `deleteTrack(track)`. Completion detection: polls `audioEngine.isPlaying()` every 250ms while in `PLAYING` state; when EOF detected and loop is enabled calls `restart()` to repeat the track, otherwise auto-transitions to `STOPPED`. Owns the `PlaybackService` lifecycle (starts on PLAYING, stops on STOPPED). Collects remote playback actions (play/pause/restart) from `PlaybackController.actions` SharedFlow.
- **`PlayerScreen`** — Compose UI: track display name at top with analysis progress indicator (or "No track loaded"), seek timeline with elapsed/remaining time labels, BPM/beats controls, centred button row with prev/play-pause/next/restart controls and loop toggle, bottom action buttons ("Tracks", "Mixer") opening respective sheets. No inline volume sliders.
- **`AudioEngine.kt`** — Kotlin JNI bridge: thin wrapper around native `AudioEngine`. Methods: `create()`, `destroy()`, `loadFile(fd, offset, length)`, `start()`, `stop()`, `pause()`, `resume()`, `seekToStart()`, `isPlaying()`, `getDurationMs()`, `getPositionMs()`, `seekTo(positionMs)`, `setFirstBeatOffset(frames)`, `analyzeBpm(fd, offset, length)` (returns `LongArray[2]` = `{bpm, firstBeatFrames}`). Property setters for BPM, beats per bar, track/click volumes propagate to native layer via `std::atomic` lock-free communication. Loads native library `tempoz`.
- **`PlaybackService`** — foreground Service managing media playback notifications and lock-screen controls. On `ACTION_START` intent, creates `MediaSessionCompat` and builds a `MediaStyle` notification with two action buttons (play/pause and restart) routed via `MediaControlReceiver` PendingIntents. Calls `startForeground()` to persist notification. On `ACTION_STOP`, releases MediaSession and calls `stopForeground(true)`. Service is owned by `PlaybackViewModel`'s lifecycle.
- **`PlaybackController`** — singleton object exposing a `SharedFlow<String>` for playback actions. `MediaControlReceiver` emits action strings (`ACTION_PLAY_PAUSE`, `ACTION_RESTART`) to this flow; `PlaybackViewModel` collects them in `init` and dispatches to the appropriate control method.
- **`MediaControlReceiver`** — `BroadcastReceiver` (exported: false) handling media button intents from the notification and lock screen. Emits action strings to `PlaybackController.actions`.
- **`data/TrackEntity`** — Room entity: `uri` (String, PK, unique index), `displayName`, `bpm`, `beatsPerBar`, `lastUsedMs` (Long), `detectedBpm` (Int?, nullable), `beatOffsetFrames` (Long?, nullable). Stored in "tracks" table.
- **`data/TrackDao`** — Room DAO: `upsert(track)`, `getAllByLastUsed(): Flow<List<TrackEntity>>` (ordered by `lastUsedMs DESC`), `deleteByUri(uri)`.
- **`data/TempozDatabase`** — Room singleton database, lazily instantiated via companion object `getInstance(context)`.
- **`data/TrackRepository`** — wrapper around `TrackDao`, delegates all operations to the DAO.
- **`TrackExplorerSheet`** — `ModalBottomSheet` composable for track persistence and discovery. Top button: "Import track" (calls `onImport`). Body: `LazyColumn` of tracks from the passed list, each row showing `displayName` + BPM/signature subtitle. Tapping a row calls `onSelectTrack(track)` and `onDismiss`. Each row wrapped in `SwipeToDismissBox` to trigger `onDeleteTrack(id)` on swipe. Empty state: "No tracks imported yet" text. Opened by "Tracks" button in `PlayerScreen`.
- **`MixerSheet`** — `ModalBottomSheet` composable for volume control. Contains two labeled sliders: one for track volume, one for click volume. Callbacks for volume changes and dismiss. Opened by "Mixer" button in `PlayerScreen`.
- **`ui/theme/`** — `Theme.kt`, `Color.kt`, `Type.kt` define the Material3 theme applied app-wide.
- **`AndroidManifest.xml`** — declares `MainActivity` as launcher; includes `READ_MEDIA_AUDIO` (API 33+) and `READ_EXTERNAL_STORAGE` (API ≤32) for runtime file access. New permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS` (API 33+). New declarations: `PlaybackService` with `foregroundServiceType="mediaPlayback"` and `exported="false"`; `MediaControlReceiver` with intent filters for play/pause and restart actions, `exported="false"`.

### C++ Audio Pipeline

All real-time audio mixing and file decoding runs in C++, compiled to a shared library `tempoz` via CMake (NDK 27.2.12479018).

- **`AudioEngine`** — Core audio engine implementing `oboe::AudioStreamDataCallback`. Owns one `ClickGenerator` and one `FileDecoder`. Methods:
  - `loadFile(fd, offset, length)` — opens an audio file via `FileDecoder::open` (invoked by `PlaybackViewModel.selectFile`).
  - `start()` — opens an Oboe output stream (`PerformanceMode::LowLatency`, `SharingMode::Exclusive`, Float stereo, 48 kHz), starts the decode thread, and configures the click generator. Sets `mIsPlaying` atomic to true.
  - `stop()` — shuts down the stream and decode thread. Sets `mIsPlaying` atomic to false.
  - `pause()` — saves current frame position to `mPauseFrameOffset`, stops the Oboe stream, calls `fileDecoder.pause()`. Sets `mIsPlaying` to false.
  - `resume()` — reopens the Oboe stream, calls `fileDecoder.resume(mPauseFrameOffset)` to seek to saved position. Sets `mIsPlaying` to true.
  - `seekToStart()` — convenience method: calls `stop()`, `fileDecoder.seekToStart()`, then `start()`.
  - `isPlaying()` — returns `mIsPlaying.load()` (atomic bool).
  - `onAudioReady` (audio callback) — reads decoded file frames + generates click frames, scales by volume, mixes (`fileVol * fileFrame + clickVol * clickFrame`), and writes to output.
  - Setters (`setBpm`, `setBeatsPerBar`, `setTrackVolume`, `setClickVolume`) use `std::atomic` for lock-free communication from Kotlin/UI thread to audio thread.

- **`ClickGenerator`** — Synthesizes a metronome click track as float32 sine bursts with exponential decay envelope. Beat 1 of each bar is 880 Hz; beats 2–N are 660 Hz. All clicks are 30 ms long. State: beat interval (in frames), current beat index, click envelope remaining, oscillator phase.

- **`FileDecoder`** — Decodes compressed audio files in a background thread. Uses `AMediaExtractor` + `AMediaCodec` to extract and decompress audio; converts decoder output to float32 (handles int16, int32, and float PCM formats); resamples to the stream's sample rate if needed via linear interpolation; upmixes mono to stereo; writes to an `oboe::FifoBuffer` (2 second ring buffer, lock-free). Audio thread calls `read()` to drain frames with zero-copy; `isEOF()` signals when all samples have been consumed. Methods: `open(fd, offset, length)`, `start()`, `stop()`, `pause()` (stops decode thread, flushes FIFO), `resume(frameOffset)` (seeks extractor via `AMediaExtractor_seekTo`, flushes codec, clears FIFO, restarts thread), `seekToStart()` (calls `resume(0)`), `read(...)`, `isEOF()`, `getFramesConsumed()` (returns atomic counter of frames consumed by reads). Thread model: `open`/`start`/`stop`/`pause`/`resume` on main thread; `read`/`isEOF`/`getFramesConsumed` on audio callback thread (no allocations in read path).

- **`jni_bridge.cpp`** — JNI glue: `nativeCreate`, `nativeDestroy`, `nativeLoadFile`, `nativeStart`, `nativeStop`, `nativePause`, `nativeResume`, `nativeSeekToStart`, `nativeIsPlaying`, `nativeSetBpm`, `nativeSetBeatsPerBar`, `nativeSetTrackVolume`, `nativeSetClickVolume`. Stores native `AudioEngine` pointer in a `jlong` handle passed to/from Kotlin.

### Build

- **`app/build.gradle.kts`** — Configures NDK 27.2, CMake at `src/main/cpp/CMakeLists.txt`, Oboe prefab integration, and C++ shared library compilation. Applies KSP plugin for Room annotation processing. Dependencies: Oboe, Room (runtime + ktx), Room compiler (KSP), androidx.media for MediaSessionCompat.
- **`CMakeLists.txt`** — Minimum 3.22.1; finds Oboe package, compiles all `.cpp` files into shared library `tempoz`, links against `oboe::oboe`, `mediandk`, `android`, `log`.
- **`gradle/libs.versions.toml`** — Version catalog includes Oboe 1.9.3, Room 2.6.x, androidx.media 1.8.x.

### SDK Constraints

`minSdk = 29` (Android 10), `targetSdk = 36` (Android 16). Oboe handles audio at sub-millisecond latencies; `MediaCodec`/`MediaExtractor` support all codec types without additional permissions on the file descriptor path.

Versions: AGP 9.2.1 · Kotlin 2.2.10 · Compose BOM 2026.02.01 · Gradle 9.4.1.

---

## Development

### Phase 1

- Android app ready for distribution on Android 16
- Read an audio file and a metronome at the same time to audio output (no sync, user-provided bpm and signature)
- Volume mixing of click and audio file

### Phase 1b

Based on user review after Phase 1:
- Add convenient media player features:
  - play/pause/restart buttons
  - Track explorer (list already imported tracks, persistent after app restart)
  - Player knows when it completes, can press play again directly.
  - Shows media playing UI in Android notification center / locked screen with play/pause/restart buttons
- UI rework, use opening menus with buttons for mixers, track selection, general improvements.
- Fill .gitignore
- Make a github pipelines
  - Release on tags v*.*.*: build, test, deploy an apk
  - Build and test on push / PR

### Phase 2

- Track timeline (seek)
- Next/Previous track buttons (between imported tracks) + loop
- Add support for Android 12..16, or even 10..16
- Import an audio file, user-provided signature, analyze and store BPM + timing offset of the recording (when to start click), store metadata
- Play an audio file with a automatic synced click based on saved metadata (automatic offset + BPM)

### Phase 3

- Add count-in of specified number of bars
- Configurable click sound, multiple options (kick, click, ping...)
- Configurable accents/ghost notes (playing round notes to 16th)
- Change BPM of a track (no pitch change)

### Phase 4

- Auto-detect signature
- Battery optimization (if needed)
- Color theme option (dark/light + accent color pick)
- Prepare Google Play Store deployment (generate documentation, description, visuals)
- Add About section describing the open-source nature of the software, form for bugs and feature requests