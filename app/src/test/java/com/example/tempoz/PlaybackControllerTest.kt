package com.example.tempoz

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackControllerTest {

    @Test
    fun emit_ACTION_PLAY_PAUSE_isReceivedOnActions() = runBlocking {
        val deferred = async(start = CoroutineStart.UNDISPATCHED) { PlaybackController.actions.first() }
        PlaybackController.emit(PlaybackController.ACTION_PLAY_PAUSE)
        assertEquals(PlaybackController.ACTION_PLAY_PAUSE, deferred.await())
    }

    @Test
    fun emit_ACTION_RESTART_isReceivedOnActions() = runBlocking {
        val deferred = async(start = CoroutineStart.UNDISPATCHED) { PlaybackController.actions.first() }
        PlaybackController.emit(PlaybackController.ACTION_RESTART)
        assertEquals(PlaybackController.ACTION_RESTART, deferred.await())
    }

    @Test
    fun constants_haveExpectedValues() {
        assertEquals("com.example.tempoz.ACTION_PLAY_PAUSE", PlaybackController.ACTION_PLAY_PAUSE)
        assertEquals("com.example.tempoz.ACTION_RESTART", PlaybackController.ACTION_RESTART)
        assertEquals("com.example.tempoz.ACTION_STOP_SERVICE", PlaybackController.ACTION_STOP_SERVICE)
    }
}
