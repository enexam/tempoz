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
- **`PlaybackViewModel`** — extends `AndroidViewModel`; owns the native `AudioEngine` instance and a `TrackRepository` for persistence. Exposes reactive state via `StateFlow`: `playbackState` (enum: `STOPPED`, `PLAYING`, `PAUSED`), `bpm`, `beatsPerBar`, `trackVolume`, `clickVolume`, `beatOffsetFrames` (editable first-beat offset, 48 kHz frames), `fileUri`, `durationMs`, `currentPositionMs`, `loopMode`, `isAnalyzing`, and `tracks` (all imported tracks). Methods: `selectFile(context, uri)` (upserts track in DB, triggers BPM analysis in IO coroutine; guards the open so a dead/permission-lost URI is dropped, not crashed), `selectTrack(track)` (loads saved BPM/beatsPerBar/beatOffset), `play()`, `pause()`, `resume()`, `restart()`, `stop()`, `seekTo(positionMs)`, `audiblePositionMs()` (latency-compensated playhead for visual sync), `setBeatOffsetFrames(frames)` (live + state), `tapTempo()` (sets `bpm` from tap intervals), `nextTrack()`, `prevTrack()`, `toggleLoop()`, `deleteTrack(track)`, `saveCurrentTrackParams()` (persists the loaded track's edited `bpm`/`beatsPerBar` without reloading audio, preserving the detected BPM and beat offset — backs the Track settings sheet's Save). All transitions map to the engine's `play()/pause()/seekTo()/stop()` primitives and are serialized on the main thread; `restart()` rewinds via `seekTo(0)` (in place when playing). Completion detection: a 100 ms poll job (active only while `PLAYING`) reads `audioEngine.getPositionMs()` and `audioEngine.isEnded()`; on EOF it loops in place via `engine.seekTo(0)` when loop is enabled (the stream stays open), otherwise auto-transitions to `STOPPED`. Owns the `PlaybackService` lifecycle (starts on PLAYING, stops on STOPPED). Collects remote playback actions (play/pause/restart) from `PlaybackController.actions` SharedFlow.
- **`PlayerScreen`** — Compose UI in a "Warm Analog" music-player layout (edge-to-edge): a `Tempoz` wordmark + analysis indicator top bar; a flexible centred middle holding a `[−] PulseCore [+]` row (the tempo steppers flank the ring), a `÷2 · ÷1.5 · ×1.5 · ×2` quick-ratio button row (`scaleBpm`, integer, clamped 40–240), the track title, and a `{bpm} bpm · {sig}/4 · auto-detected` caption (or "No track loaded"); then a bottom-pinned control block — the seek timeline with elapsed/remaining labels, a transport row (prev / restart / play-pause / next / loop), and a five-item quick-access dock. The ring itself is tap-tempo (`viewModel.tapTempo()`) and is fed `beatOffsetFrames` + `viewModel.audiblePositionMs` for audio-locked animation. The dock opens bottom sheets: **Tracks** (`TrackExplorerSheet`), **Track** (`TrackSettingsSheet`, disabled until a track is loaded), **Click** (`ClickSoundSheet`), **Mixer** (`MixerSheet`), **Speed** (`SpeedSheet`). Holds UI-only state for the not-yet-wired click-sound and playback-speed selections.
- **`PulseCore`** — the "album-art" centerpiece (`ui/PulseCore.kt`): a warm metronome face drawn on `Canvas` whose pulse is **locked to the audible click**. A `withFrameNanos` loop reads `audiblePositionMs()` (the latency-compensated playhead) every frame, finds the current beat via `BeatGrid`, and derives the bloom envelope purely from time-since-onset — so the visual beat tracks what you hear instead of free-running/drifting. `bpm`/`beatsPerBar`/`firstBeatOffsetFrames` are read live (`rememberUpdatedState`). Renders a rust bloom + scale, rim beat ticks with the active beat lit, and the BPM in Fraunces, with a `BEAT n / N` (or signature when stopped) readout. The face is tappable for **tap-tempo** (`onTap`), and `ringSize` is parameterized so the screen can flank it with the tempo steppers.
- **`TrackSettingsSheet`** — `ModalBottomSheet` to configure the loaded track's metronome params: a BPM slider with `−`/`+` fine steppers, time-signature `FilterChip`s (2–8), an editable **FIRST BEAT** offset (ms) — a 0–3000 ms slider with ±10 ms steppers (`onBeatOffsetChange` → `setBeatOffsetFrames`, applied live) to line the click up with the recording's downbeat, an auto-detected `{detectedBpm} bpm` reference with a "Use" shortcut, and a "Save to track" button calling `saveCurrentTrackParams()`. BPM/signature/offset edits apply to the engine live; Save persists them.
- **`ClickSoundSheet`** — `ModalBottomSheet` selecting the click voice (Click/Rim/Wood block/Beep/Cowbell/Hi-hat). UI-only (no audio backend yet); selection held in `PlayerScreen` state, with an honest "not wired" note.
- **`SpeedSheet`** — `ModalBottomSheet` with a 0.5×–1.5× playback-speed slider + preset chips. UI-only (the engine has no time-stretch yet); honest "not wired" note.
- **`SheetCommon`** — shared `SheetTitle(title, subtitle)` header used by all quick-access sheets.
- **`AudioEngine.kt`** — Kotlin JNI bridge: thin wrapper around native `AudioEngine`. Methods: `create()`, `destroy()`, `loadFile(fd, offset, length)`, `start()` (native `play()`), `stop()`, `pause()`, `resume()`, `seekToStart()`, `isPlaying()`, `isEnded()`, `getDurationMs()`, `getPositionMs()`, `getAudiblePositionMs()`, `seekTo(positionMs)`, `setFirstBeatOffset(frames)`, `analyzeBpm(fd, offset, length)` (returns `DoubleArray[3]` = `{bpm, firstBeatFrames, beatsPerBar}`). Property setters for BPM, beats per bar, track/click volumes propagate to the native layer via `std::atomic` lock-free communication. Loads native library `tempoz`.
- **`BeatGrid`** — pure-Kotlin object holding the canonical metronome beat-grid math (`beatIntervalFrames`, `beatIndexAt`, `beatOnsetFrame`, `isAccent`), the single source of truth mirrored by the native `ClickGenerator`. Dependency-free and position-only, so it is unit-tested on the JVM (`BeatGridTest`: drift-free spacing, fractional-BPM rounding, accent placement, offset handling, seek realignment) — the one sync-critical piece verifiable without a device.
- **`PlaybackService`** — foreground Service managing media playback notifications and lock-screen controls. On `ACTION_START` intent, creates `MediaSessionCompat` and builds a `MediaStyle` notification with two action buttons (play/pause and restart) routed via `MediaControlReceiver` PendingIntents. Calls `startForeground()` to persist notification. On `ACTION_STOP`, releases MediaSession and calls `stopForeground(true)`. Service is owned by `PlaybackViewModel`'s lifecycle.
- **`PlaybackController`** — singleton object exposing a `SharedFlow<String>` for playback actions. `MediaControlReceiver` emits action strings (`ACTION_PLAY_PAUSE`, `ACTION_RESTART`) to this flow; `PlaybackViewModel` collects them in `init` and dispatches to the appropriate control method.
- **`MediaControlReceiver`** — `BroadcastReceiver` (exported: false) handling media button intents from the notification and lock screen. Emits action strings to `PlaybackController.actions`.
- **`data/TrackEntity`** — Room entity: `uri` (String, PK, unique index), `displayName`, `bpm`, `beatsPerBar`, `lastUsedMs` (Long), `detectedBpm` (Int?, nullable), `beatOffsetFrames` (Long?, nullable). Stored in "tracks" table.
- **`data/TrackDao`** — Room DAO: `upsert(track)`, `getAllByLastUsed(): Flow<List<TrackEntity>>` (ordered by `lastUsedMs DESC`), `deleteByUri(uri)`.
- **`data/TempozDatabase`** — Room singleton database, lazily instantiated via companion object `getInstance(context)`.
- **`data/TrackRepository`** — wrapper around `TrackDao`, delegates all operations to the DAO.
- **`TrackExplorerSheet`** — "Library" `ModalBottomSheet` for track persistence and discovery. Header (`SheetTitle` + count), a prominent "Import track" button (calls `onImport`), then a height-bounded `LazyColumn` of styled rows (music-note avatar, `displayName`, `{bpm} bpm · {sig}/4` subtitle). The currently loaded track is highlighted (`secondaryContainer` + "now" badge); takes `currentUri` to detect it. Tapping a row calls `onSelectTrack(track)` + `onDismiss`; each row is wrapped in `SwipeToDismissBox` with a red delete panel to trigger `onDeleteTrack(track)`. Empty state text. Opened by the "Tracks" dock item.
- **`MixerSheet`** — `ModalBottomSheet` for volume control. Two iconed, labeled rows (Track, Click) each with a live percentage and a slider. Opened by the "Mixer" dock item.
- **`ui/theme/`** — `Theme.kt`, `Color.kt`, `Type.kt` define the hand-tuned **Warm Analog** Material3 theme (cream / walnut / rust). Dynamic color is intentionally disabled; every M3 role is specified for both a light (hero) and a coherent dark (espresso) scheme. Typography pairs bundled variable fonts **Fraunces** (display/serif headings, `res/font/fraunces.ttf`) and **Newsreader** (body/labels, `res/font/newsreader.ttf`), realized per-weight via `FontVariation`. `Theme.kt` also sets system-bar icon contrast for the edge-to-edge layout.
- **`AndroidManifest.xml`** — declares `MainActivity` as launcher; includes `READ_MEDIA_AUDIO` (API 33+) and `READ_EXTERNAL_STORAGE` (API ≤32) for runtime file access. New permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS` (API 33+). New declarations: `PlaybackService` with `foregroundServiceType="mediaPlayback"` and `exported="false"`; `MediaControlReceiver` with intent filters for play/pause and restart actions, `exported="false"`.

### C++ Audio Pipeline

All real-time audio mixing and file decoding runs in C++, compiled to a shared library `tempoz` via CMake (NDK 27.2.12479018).

- **`AudioEngine`** — Core audio engine implementing `oboe::AudioStreamDataCallback`, structured as a deterministic state machine. Owns one `ClickGenerator` and one `FileDecoder`. Design invariants (these are what make playback reliable): the Oboe stream is opened **once and kept open** for the engine's lifetime — pause/resume/seek only `stop()`/`start()` it, never `close()` (closed only in the destructor), which is what removed the seeker failures from churning a stream; the stream is fixed at 48 kHz via Oboe's sample-rate conversion (`SharingMode::Shared`, `PerformanceMode::None`), so every frame count (playhead, duration, click grid) is in one unit and the click can't drift on non-48k devices; there is exactly one playhead, `mPositionFrames` (atomic int64), advanced by the audio thread while playing and written by seek/stop only while the stream is stopped (STOPPED→0, PAUSED→frozen); the callback reads the decoder FIFO only when `mIsPlaying` is true, and every reseed sets `mIsPlaying=false` and stops the stream first, keeping the FIFO single-producer/single-consumer; the callback never touches `mStream` and never returns `Stop` (lifecycle is owned by the main thread). Methods:
  - `loadFile(fd, offset, length)` — stops any current playback, (re)opens the file via `FileDecoder::open`, resets the playhead to 0 and clears the ended flag.
  - `play()` (and `resume()`, its alias) — opens the stream if needed, seeds the decoder at the current playhead (`FileDecoder::startAt`), then `mStream->start()`. Starts from 0 when stopped, from the frozen playhead when paused.
  - `pause()` — sets `mIsPlaying=false`, `mStream->stop()` (blocking barrier so the in-flight callback has exited), `fileDecoder.stop()`; the playhead is left frozen.
  - `seekTo(positionMs)` — clamps to duration; if playing, stops the stream (barrier), sets the playhead, reseeds the decoder, and restarts the same open stream; if paused/stopped, just sets the playhead (the next play seeds there). `seekToStart()` = `seekTo(0)`.
  - `stop()` — stops the stream (kept open) and the decoder, resets the playhead to 0.
  - `isPlaying()` / `isEnded()` — atomic bool reads. `isEnded()` is set by the callback at EOF and cleared by seek/play/loadFile; the ViewModel polls it for completion and looping.
  - `getDurationMs()` / `getPositionMs()` — `getDurationFrames()` and `mPositionFrames` (clamped to duration), each `* 1000 / 48000`.
  - `getAudiblePositionMs()` — the frame currently **at the speaker**, for locking the visual beat to what's heard: interpolates `mPositionFrames` forward from the last callback's `CLOCK_MONOTONIC` snapshot, then subtracts the stream's output latency (`calculateLatencyMillis()`, cached ~500 ms). Falls back to `getPositionMs()` when not playing.
  - `onAudioReady` (audio callback) — when playing, reads file frames + renders the click as a pure function of the absolute playhead, mixes by volume, clamps, advances the playhead, and flags EOF; otherwise writes silence. Never touches the stream.
  - Setters (`setBpm`, `setBeatsPerBar`, `setTrackVolume`, `setClickVolume`, `setFirstBeatOffset`) use `std::atomic` for lock-free communication from the UI thread to the audio thread.

- **`ClickGenerator`** — Synthesizes the metronome click as float32 sine bursts with an exponential-decay envelope, computed as a **pure function of absolute playback position** (no carried beat state). Beat *k* (counted from `firstBeatOffset`) has onset `firstBeatOffset + round(k * beatIntervalFrames)`; beat 0 of each bar is 880 Hz, others 660 Hz; each click is 30 ms. `render(out, numFrames, channels, startFrame, bpm, beatsPerBar, firstBeatOffset, sampleRate, gain)` sums the clicks falling in `[startFrame, startFrame+numFrames)`. Because it is position-derived, the click realigns to the beat grid automatically after any seek/pause and cannot drift. The beat-grid math mirrors `BeatGrid.kt`, which carries the JVM unit tests (`BeatGridTest`); keep the two in lockstep.

- **`FileDecoder`** — Decodes compressed audio files in a background thread. Uses `AMediaExtractor` + `AMediaCodec`; converts decoder output to float32 (int16/int32/float PCM); linear-resamples to 48 kHz when needed; upmixes mono to stereo; writes to an `oboe::FifoBuffer` (2 s lock-free ring). Lifecycle is two primitives with one hard invariant — **a decode thread is never launched over a still-joinable one** (the previous design's `std::thread` reassignment was the seeker crash): `startAt(frameOffset)` (stops+joins any running thread, seeks the extractor, flushes/starts the codec, clears the FIFO, relaunches — also the **seek** primitive) and `stop()` (stops+joins the thread, halts the codec). `open()` is **reentrant**: it releases the previous extractor/codec/FIFO before reallocating, so track switches don't leak codec instances (a limited system resource — the old leak made switching fail after a few). Audio thread calls `read()` (drains frames; `try_lock`s `mFifoMutex` and outputs silence if a reseed is mid-flush, so it never blocks the audio thread) and `isEOF()`. `startAt()`/`open()` mutate the FIFO counters only while the engine has the Oboe stream stopped, keeping the FIFO strictly SPSC. Thread model: `open`/`startAt`/`stop` on the main thread (serialized by the engine); `read`/`isEOF`/`getFramesConsumed`/`getDurationFrames` on the audio callback thread (no allocations in the read path).

- **`BpmAnalyzer`** — Stateless helper class that detects tempo and first-beat offset from an audio file. `analyze(fd, offset, length)` returns `BpmResult{int bpm, int64_t firstBeatFrames}`. Algorithm: opens its own `AMediaExtractor`+`AMediaCodec` pipeline (independent of the playback decoder); decodes up to 30 s to mono float32; computes a short-time onset-strength envelope (10 ms hop, half-wave-rectified energy delta); autocorrelates the envelope over lags for 40–240 BPM weighted by a Gaussian centred at 120 BPM; returns the peak-lag BPM and the first onset above `mean + 1.5σ` as `firstBeatFrames` (normalized to 48 kHz). Returns `{0, 0}` on failure or silent input.

- **`jni_bridge.cpp`** — JNI glue: `nativeCreate`, `nativeDestroy`, `nativeLoadFile`, `nativeStart` (→`play()`), `nativeStop`, `nativePause`, `nativeResume`, `nativeSeekToStart`, `nativeIsPlaying`, `nativeIsEnded`, `nativeSetBpm`, `nativeSetBeatsPerBar`, `nativeSetTrackVolume`, `nativeSetClickVolume`, `nativeGetDurationMs`, `nativeGetPositionMs`, `nativeGetAudiblePositionMs`, `nativeSeekTo`, `nativeSetFirstBeatOffset`, `nativeAnalyzeBpm` (returns `jdoubleArray[3]` = `{bpm, firstBeatFrames, beatsPerBar}`). Stores the native `AudioEngine` pointer in a `jlong` handle passed to/from Kotlin.

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
- Import an audio file, analyze and store BPM + time signature + timing offset of the recording (when to start click), store metadata
- Play an audio file with a automatic synced click based on saved metadata (automatic offset + BPM)

### Phase 3

- Add count-in of specified number of bars
- Configurable click sound, multiple options (kick, click, ping...)
- Configurable accents/ghost notes (playing round notes to 16th)
- Change BPM of a track (no pitch change)
- Fractional BPM in the UI: the engine already plays fractional BPM (auto-detect sets e.g. 98.50 and plays it drift-free), but the display rounds to a whole number and the −/+ steppers + tap-tempo snap to integer — so the precision is hidden and destroyed on the first manual edit. Show decimals + a fine (±0.1) adjust. This is the real fix for the ~1.6-beat-per-song drift that integer rounding causes on a true-98.50 track like Fake Happy (confirmed by the playground DSP analysis: tempo is dead-constant, so a single fractional BPM tracks it the whole way).
- Assisted first-beat alignment: a guided tool that plays the track up to the estimated first beat and asks the user "has the first beat happened yet?", narrowing `beatOffsetFrames` by binary search until the click locks onto the recording's downbeat. (Manual ms editing of the offset ships earlier, in the Track sheet.)
- Tap-tempo phase lock: when setting BPM by tapping the Pulse Core, also align the beat-grid phase (`firstBeatOffset`) to the taps so the click syncs to the tapped downbeat, not just the tempo. (Tap-to-set-BPM ships earlier; phase alignment is the follow-up.)

### Phase 4

- Battery optimization (if needed)
- Color theme option (dark/light + accent color pick)
- Remove the `BEAT n / N` readout under the Pulse Core — the ring's lit beat ticks already show the current beat, so the text is redundant.
- Prepare Google Play Store deployment: documentation, description, visuals; **release signing** — generate an upload keystore and enrol in Play App Signing, add a `release` build type wired to the signing config with R8/minify, set real `versionCode`/`versionName`, and review the application id, `minSdk`/`targetSdk`, and the release permission set (a signed release AAB, not the debug-keystore APK CI builds today).
- Add an **About menu** (model on `../checkpoint`'s `src/checkpoint/about.py`): shows the app name + **version** (from `versionName`), a one-line description, the open-source license, and the author **Maxence "Enexam" Beuselinck**; with buttons for the **GitHub repository** (`https://github.com/enexam/tempoz`), **Report a Bug**, and **Request a Feature** — the latter two opening a pre-filled GitHub new-issue URL (`/issues/new?title=…&body=…&labels=…`) with a `[Bug]`/`[Feature]` title, a body template carrying the app version + Android version/device, and `bug`/`enhancement` labels (cf. checkpoint's `build_issue_url`).
- **Tag-driven version in CI**: on `v*.*.*` tags the release pipeline derives `versionName` (and bumps `versionCode`) from the git tag (strip the leading `v`) and injects it into the build, so the released artifact and the About screen's version always match the tag automatically — the Android/Gradle analogue of checkpoint's `release.yml` "Set version from tag" step.