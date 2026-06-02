# Tempoz

Tempoz is an Android app that adds a metronome to music tracks. Load any audio file, set the BPM and time signature, and play your track with a click alongside it — with independent volume control for each.

## Features

- Play an audio file and a metronome click track simultaneously
- BPM and time signature (beats per bar) control
- Automatic BPM detection and beat offset analysis when importing tracks
- Synchronized metronome playback aligned to the detected beat offset
- Seek timeline with elapsed and remaining time display
- Next/previous track navigation with circular looping through imported tracks
- Loop mode toggle to repeat a track indefinitely
- Independent volume mixing for the audio track and the click
- Play / pause / restart controls
- Track explorer: import and persist multiple tracks; tap to reload saved BPM, signature, and auto-detected settings
- Media notification and lock-screen controls (play/pause, restart)

## Requirements

- Android 10 (API 29) or later
- A connected device or emulator for installation

## Build

All commands run from the project root using the Gradle wrapper.

```
.\gradlew assembleDebug          # build debug APK
.\gradlew installDebug           # build and install on a connected device/emulator
.\gradlew test                   # run unit tests
.\gradlew lint                   # run lint
```

The project uses NDK 27.2 and CMake. The native audio pipeline is built automatically as part of the Gradle build.

## Architecture

- **Kotlin + Jetpack Compose** — single-activity UI, Material3 theme
- **C++ audio engine** — Oboe for low-latency output; `AMediaExtractor` + `AMediaCodec` for file decoding; custom click generator producing sine-burst clicks with exponential decay
- **Room** — local SQLite database for track persistence (URI, display name, BPM, beats per bar)
- **Android foreground Service** — `MediaStyle` notification with `MediaSessionCompat` for lock-screen and notification-shade controls

See [`.claude/CLAUDE.md`](.claude/CLAUDE.md) for a detailed architecture reference.

## Roadmap

- **Phase 2** ✓ — seek timeline, next/previous track, BPM analysis and auto-sync
- **Phase 3** — count-in, configurable click sounds, accent patterns, tempo change
- **Phase 4** — auto-detect time signature, Google Play deployment
