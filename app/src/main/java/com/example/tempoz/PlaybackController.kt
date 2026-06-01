package com.example.tempoz

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object PlaybackController {
    const val ACTION_PLAY_PAUSE = "com.example.tempoz.ACTION_PLAY_PAUSE"
    const val ACTION_RESTART = "com.example.tempoz.ACTION_RESTART"
    const val ACTION_STOP_SERVICE = "com.example.tempoz.ACTION_STOP_SERVICE"

    private val _actions = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val actions: SharedFlow<String> = _actions.asSharedFlow()

    fun emit(action: String) { _actions.tryEmit(action) }
}
