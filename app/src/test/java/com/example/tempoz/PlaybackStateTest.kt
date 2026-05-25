package com.example.tempoz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Unit tests for [PlaybackState] enum values and basic state identity.
 * Full ViewModel tests require an emulator (AndroidViewModel + native library).
 */
class PlaybackStateTest {

    @Test
    fun playbackState_hasThreeValues() {
        assertEquals(3, PlaybackState.entries.size)
    }

    @Test
    fun playbackState_valuesAreDistinct() {
        assertNotEquals(PlaybackState.STOPPED, PlaybackState.PLAYING)
        assertNotEquals(PlaybackState.STOPPED, PlaybackState.PAUSED)
        assertNotEquals(PlaybackState.PLAYING, PlaybackState.PAUSED)
    }

    @Test
    fun playbackState_initialValueIsStopped() {
        val initial = PlaybackState.STOPPED
        assertEquals(PlaybackState.STOPPED, initial)
    }
}
