package com.pranshulgg.recordmaster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import com.pranshulgg.recordmaster.helpers.PlaybackController
import com.pranshulgg.recordmaster.helpers.PreferencesHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Media notification for playing a recording: lock-screen / notification controls with
 * play-pause, -5 s, +10 s, stop and a seek bar, and playback that survives switching apps.
 * The playback itself is owned by [PlaybackController].
 */
class PlaybackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: MediaSession? = null
    private var observing: Job? = null
    private var metadataKey: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        PreferencesHelper.init(this)
        createChannel()
        session = MediaSession(this, "ExpressiveVoicePlayback").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = PlaybackController.play()
                override fun onPause() = PlaybackController.pause()
                override fun onSeekTo(pos: Long) = PlaybackController.seekTo(pos.toInt())
                override fun onRewind() = PlaybackController.seekBy(-REWIND_MS)
                override fun onFastForward() = PlaybackController.seekBy(FORWARD_MS)
                override fun onStop() = PlaybackController.stop()
                // Android 13+ builds the media controls from the session, not from the notification
                // buttons, so -5 s / +10 s / stop have to be offered as custom actions here.
                override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                    when (action) {
                        CUSTOM_REWIND -> PlaybackController.seekBy(-REWIND_MS)
                        CUSTOM_FORWARD -> PlaybackController.seekBy(FORWARD_MS)
                        CUSTOM_STOP -> PlaybackController.stop()
                    }
                }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> PlaybackController.toggle()
            ACTION_REWIND -> PlaybackController.seekBy(-REWIND_MS)
            ACTION_FORWARD -> PlaybackController.seekBy(FORWARD_MS)
            ACTION_STOP -> PlaybackController.stop()
            ACTION_SYNC -> {
                // Started with startForegroundService: must go to the foreground right away.
                startInForeground(buildNotification())
            }
        }
        if (PlaybackController.path.value == null) {
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }
        if (observing == null) {
            observing = scope.launch {
                PlaybackController.version.collect { refresh() }
            }
        }
        return START_NOT_STICKY
    }

    private fun refresh() {
        val path = PlaybackController.path.value
        if (path == null) {
            stopForegroundAndSelf()
            return
        }
        val s = session ?: return
        val duration = PlaybackController.duration.value
        val key = "$path|$duration"
        if (key != metadataKey) {
            metadataKey = key
            s.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, File(path).nameWithoutExtension)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, getString(R.string.app_name))
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, duration.toLong())
                    .build()
            )
        }
        val playing = PlaybackController.isPlaying.value
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_REWIND or PlaybackState.ACTION_FAST_FORWARD or
                        PlaybackState.ACTION_STOP
                )
                .addCustomAction(
                    PlaybackState.CustomAction.Builder(CUSTOM_REWIND, "Back 5 seconds", R.drawable.replay_5_24px).build()
                )
                .addCustomAction(
                    PlaybackState.CustomAction.Builder(CUSTOM_FORWARD, "Forward 10 seconds", R.drawable.forward_10_24px).build()
                )
                .addCustomAction(
                    PlaybackState.CustomAction.Builder(CUSTOM_STOP, "Stop", R.drawable.close_24px).build()
                )
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackController.position().toLong(),
                    if (playing) 1f else 0f
                )
                .build()
        )
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun startInForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun stopForegroundAndSelf() {
        observing?.cancel()
        observing = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controls while a recording is playing"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun pending(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code,
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun action(icon: Int, title: String, action: String, code: Int): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(this, icon), title, pending(action, code))
            .build()

    private fun buildNotification(): Notification {
        val path = PlaybackController.path.value
        val name = path?.let { File(it).nameWithoutExtension } ?: getString(R.string.app_name)
        val playing = PlaybackController.isPlaying.value

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder
            .setSmallIcon(R.drawable.graphic_eq_24px)
            .setContentTitle(name)
            .setContentText(getString(R.string.app_name))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(R.drawable.replay_5_24px, "Back 5 seconds", ACTION_REWIND, 11))
            .addAction(
                if (playing) action(R.drawable.pause_24px, "Pause", ACTION_TOGGLE, 12)
                else action(R.drawable.play_arrow_24px, "Play", ACTION_TOGGLE, 12)
            )
            .addAction(action(R.drawable.forward_10_24px, "Forward 10 seconds", ACTION_FORWARD, 13))
            .addAction(action(R.drawable.close_24px, "Stop", ACTION_STOP, 14))

        val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
        session?.sessionToken?.let { style.setMediaSession(it) }
        builder.setStyle(style)
        return builder.build()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.release()
        session = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 4102
        private const val REWIND_MS = 5_000
        private const val FORWARD_MS = 10_000
        private const val CUSTOM_REWIND = "rewind5"
        private const val CUSTOM_FORWARD = "forward10"
        private const val CUSTOM_STOP = "stop"
        const val ACTION_SYNC = "com.captechomar.recorder.action.PLAYBACK_SYNC"
        const val ACTION_TOGGLE = "com.captechomar.recorder.action.PLAYBACK_TOGGLE"
        const val ACTION_REWIND = "com.captechomar.recorder.action.PLAYBACK_REWIND"
        const val ACTION_FORWARD = "com.captechomar.recorder.action.PLAYBACK_FORWARD"
        const val ACTION_STOP = "com.captechomar.recorder.action.PLAYBACK_STOP"
    }
}
