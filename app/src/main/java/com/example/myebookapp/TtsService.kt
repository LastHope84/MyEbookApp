package com.example.myebookapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Bridges the reader's speech controller to the system media controls. */
object TtsPlayback {
    var controller: TtsController? = null
    var state: TtsState = TtsState.IDLE
    var title: String = ""
    var bookUri: String? = null

    @Volatile
    var serviceRunning = false

    fun toggle() {
        val current = controller ?: return
        when (current.currentState) {
            TtsState.PLAYING -> current.pause()
            TtsState.PAUSED -> current.resume()
            TtsState.IDLE -> Unit
        }
    }

    fun pause() {
        controller?.pause()
    }

    fun resume() {
        controller?.resume()
    }

    fun stop() {
        controller?.stop()
    }
}

class TtsService : Service() {
    companion object {
        const val ACTION_UPDATE = "com.example.myebookapp.tts.UPDATE"
        const val ACTION_PAUSE = "com.example.myebookapp.tts.PAUSE"
        const val ACTION_RESUME = "com.example.myebookapp.tts.RESUME"
        const val ACTION_STOP = "com.example.myebookapp.tts.STOP"
        private const val CHANNEL_ID = "tts_playback"
        private const val NOTIFICATION_ID = 4201

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TtsService::class.java).setAction(ACTION_UPDATE)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TtsService::class.java))
        }
    }

    private lateinit var session: MediaSessionCompat

    override fun onCreate() {
        super.onCreate()
        createChannel()
        session = MediaSessionCompat(this, "MyEbookAppTts").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = TtsPlayback.resume()
                override fun onPause() = TtsPlayback.pause()
                override fun onStop() = TtsPlayback.stop()
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> TtsPlayback.pause()
            ACTION_RESUME -> TtsPlayback.resume()
            ACTION_STOP -> TtsPlayback.stop()
        }

        val state = TtsPlayback.state
        if (state == TtsState.IDLE) {
            endPlayback()
            return START_NOT_STICKY
        }
        updateSession(state)
        startForeground(NOTIFICATION_ID, buildNotification(state))
        TtsPlayback.serviceRunning = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        TtsPlayback.serviceRunning = false
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun endPlayback() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        TtsPlayback.serviceRunning = false
        stopSelf()
    }

    private fun buildNotification(state: TtsState): Notification {
        val playing = state == TtsState.PLAYING
        val toggleAction = if (playing) ACTION_PAUSE else ACTION_RESUME
        val toggleIcon = if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val toggleLabel = if (playing) "Pause" else "Play"
        val toggleIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TtsService::class.java).setAction(toggleAction),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 2,
            Intent(this, TtsService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val contentIntent = TtsPlayback.bookUri?.let { uri ->
            PendingIntent.getActivity(
                this, 3,
                Intent(this, ReaderActivity::class.java).apply { putExtra("book_uri", uri) },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tts)
            .setContentTitle(TtsPlayback.title.ifBlank { "Reading aloud" })
            .setContentText(if (playing) "Reading aloud" else "Paused")
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(toggleIcon, toggleLabel, toggleIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
        contentIntent?.let { builder.setContentIntent(it) }
        return builder.build()
    }

    private fun updateSession(state: TtsState) {
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, TtsPlayback.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "MyEbookApp")
                .build()
        )
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE
            )
            .setState(
                if (state == TtsState.PLAYING) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                1f
            )
            .build()
        session.setPlaybackState(playbackState)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Reading aloud",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controls for reading a book aloud"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
