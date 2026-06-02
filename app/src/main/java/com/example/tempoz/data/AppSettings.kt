package com.example.tempoz.data

/**
 * Immutable snapshot of the app's global settings.
 *
 * Defaults:
 * - [clickSound]: "Click" — the synthesized voice used for the metronome.
 * - [subdivision]: 1 — beats only (no sub-beats). 2=eighth, 3=triplet, 4=sixteenth.
 * - [ghostVolume]: 0.35 — relative volume of subdivision ghost clicks vs. beat clicks.
 * - [countInBars]: 0 — count-in bars before playback starts from STOPPED (0 = off).
 * - [defaultTrackVolume]: 1.0 — volume applied to new sessions / applied at startup.
 * - [defaultClickVolume]: 0.8 — click volume applied to new sessions / applied at startup.
 */
data class AppSettings(
    val clickSound: String = "Click",
    val subdivision: Int = 1,
    val ghostVolume: Float = 0.35f,
    val countInBars: Int = 0,
    val defaultTrackVolume: Float = 1.0f,
    val defaultClickVolume: Float = 0.8f,
)
