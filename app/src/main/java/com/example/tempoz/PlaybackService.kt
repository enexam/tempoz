package com.example.tempoz

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle

class PlaybackService : Service() {

    companion object {
        const val ACTION_START = "com.example.tempoz.SERVICE_START"
        const val ACTION_STOP = "com.example.tempoz.SERVICE_STOP"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "tempoz_playback"
    }

    private var mediaSession: MediaSessionCompat? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val trackName = intent.getStringExtra("trackName") ?: "Unknown"
                val isPlaying = intent.getBooleanExtra("isPlaying", false)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel = NotificationChannel(
                        CHANNEL_ID,
                        "Tempoz Playback",
                        NotificationManager.IMPORTANCE_LOW
                    )
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.createNotificationChannel(channel)
                }

                if (mediaSession == null) {
                    mediaSession = MediaSessionCompat(this, "TempozSession").also {
                        it.isActive = true
                    }
                }

                val notification = buildNotification(trackName, isPlaying)

                // Every startForegroundService() must be matched by startForeground()
                // within the system timeout, or it throws ForegroundServiceDidNotStartIn
                // TimeException. Since the ViewModel re-issues ACTION_START on each
                // play/pause, always call startForeground() — with the same id it just
                // updates the existing notification.
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            }
            ACTION_STOP -> {
                mediaSession?.release()
                mediaSession = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(trackName: String, isPlaying: Boolean): android.app.Notification {
        val playPauseIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(PlaybackController.ACTION_PLAY_PAUSE).apply {
                setClass(this@PlaybackService, MediaControlReceiver::class.java)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val restartIntent = PendingIntent.getBroadcast(
            this,
            1,
            Intent(PlaybackController.ACTION_RESTART).apply {
                setClass(this@PlaybackService, MediaControlReceiver::class.java)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val playPauseIcon = if (isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(trackName)
            .setContentText("Tempoz")
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession!!.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .addAction(playPauseIcon, "Play/Pause", playPauseIntent)
            .addAction(android.R.drawable.ic_media_previous, "Restart", restartIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }
}
