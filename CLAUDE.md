# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Tempoz

Tempoz is an Android app that adds a metronome to music tracks: simultaneous playback of an audio file and a click track, with BPM control, count-in, and volume mixing.

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

- **`MainActivity`** — sole entry point; hosts the Compose UI tree via `setContent` + `PlayerScreen` composable.
- **`PlaybackViewModel`** — state management (BPM, beats per bar, volumes, file selection, playback state); owns the native `AudioEngine` instance.
- **`PlayerScreen`** — Compose UI with file picker, BPM/beats-per-bar controls, volume sliders, and play/stop button.
- **`AudioEngine.kt`** — Kotlin JNI bridge: thin wrapper around native `AudioEngine` with property setters for BPM, beats per bar, track/click volumes; loads native library `tempoz`.
- **`ui/theme/`** — `Theme.kt`, `Color.kt`, `Type.kt` define the Material3 theme applied app-wide.
- **`AndroidManifest.xml`** — declares `MainActivity` as launcher; includes `READ_MEDIA_AUDIO` (API 33+) and `READ_EXTERNAL_STORAGE` (API ≤32) for runtime file access.

### C++ Audio Pipeline

All real-time audio mixing and file decoding runs in C++, compiled to a shared library `tempoz` via CMake (NDK 27.2.12479018).

- **`AudioEngine`** — Core audio engine implementing `oboe::AudioStreamDataCallback`. Owns one `ClickGenerator` and one `FileDecoder`. Methods:
  - `loadFile(fd, offset, length)` — opens an audio file via `FileDecoder::open` (invoked by `PlaybackViewModel.selectFile`).
  - `start()` — opens an Oboe output stream (`PerformanceMode::LowLatency`, `SharingMode::Exclusive`, Float stereo, 48 kHz), starts the decode thread, and configures the click generator.
  - `stop()` — shuts down the stream and decode thread.
  - `onAudioReady` (audio callback) — reads decoded file frames + generates click frames, scales by volume, mixes (`fileVol * fileFrame + clickVol * clickFrame`), and writes to output.
  - Setters (`setBpm`, `setBeatsPerBar`, `setTrackVolume`, `setClickVolume`) use `std::atomic` for lock-free communication from Kotlin/UI thread to audio thread.

- **`ClickGenerator`** — Synthesizes a metronome click track as float32 sine bursts with exponential decay envelope. Beat 1 of each bar is 880 Hz; beats 2–N are 660 Hz. All clicks are 30 ms long. State: beat interval (in frames), current beat index, click envelope remaining, oscillator phase.

- **`FileDecoder`** — Decodes compressed audio files in a background thread. Uses `AMediaExtractor` + `AMediaCodec` to extract and decompress audio; converts decoder output to float32 (handles int16, int32, and float PCM formats); resamples to the stream's sample rate if needed via linear interpolation; upmixes mono to stereo; writes to an `oboe::FifoBuffer` (2 second ring buffer, lock-free). Audio thread calls `read()` to drain frames with zero-copy; `isEOF()` signals when all samples have been consumed. Thread model: `open`/`start`/`stop` on main thread, `read`/`isEOF` on audio thread (no allocations).

- **`jni_bridge.cpp`** — JNI glue: `nativeCreate`, `nativeDestroy`, `nativeLoadFile`, `nativeStart`, `nativeStop`, `nativeSetBpm`, `nativeSetBeatsPerBar`, `nativeSetTrackVolume`, `nativeSetClickVolume`. Stores native `AudioEngine` pointer in a `jlong` handle passed to/from Kotlin.

### Build

- **`app/build.gradle.kts`** — Configures NDK 27.2, CMake at `src/main/cpp/CMakeLists.txt`, Oboe prefab integration, and C++ shared library compilation.
- **`CMakeLists.txt`** — Minimum 3.22.1; finds Oboe package, compiles all `.cpp` files into shared library `tempoz`, links against `oboe::oboe`, `mediandk`, `android`, `log`.
- **`gradle/libs.versions.toml`** — Version catalog includes Oboe 1.9.3.

### SDK Constraints

`minSdk = 29` (Android 10), `targetSdk = 36` (Android 16). Oboe handles audio at sub-millisecond latencies; `MediaCodec`/`MediaExtractor` support all codec types without additional permissions on the file descriptor path.

Versions: AGP 9.2.1 · Kotlin 2.2.10 · Compose BOM 2026.02.01 · Gradle 9.4.1.

---

## Development

### Phase 1 — 1p × 2–3 weeks

- Android app ready for distribution on Android 16
- Read an audio file and a metronome at the same time to audio output (no sync, user-provided bpm and signature)
- Volume mixing of click and audio file

### Phase 2 — 1p × 4–6 weeks

- Add support for Android 12..16, or even 10..16
- Import an audio file, user-provided signature, analyze and store BPM + timing offset of the recording (when to start click), store metadata
- Read an audio file with a synced click based on saved metadata (automatic offset + BPM)

### Phase 3 — 1p × 3–5 weeks

- Add count-in of specified number of bars
- Configurable click sound, multiple options (kick, click, ping...)
- Configurable accents/ghost notes (playing round notes to 16th)
- Change BPM of a track (no pitch change)

### Phase 4 — 1p × 2–4 weeks

- Auto-detect signature
- Battery optimization
- Run with screen off
- Prepare Google Play Store deployment (generate documentation, description, visuals)
- Add About section describing the open-source nature of the software, form for bugs and feature requests