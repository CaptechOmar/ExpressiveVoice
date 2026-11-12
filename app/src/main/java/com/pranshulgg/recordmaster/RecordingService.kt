package com.pranshulgg.recordmaster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pranshulgg.recordmaster.helpers.PreferencesHelper
import com.pranshulgg.recordmaster.helpers.RecorderController
import com.pranshulgg.recordmaster.helpers.RecorderController.Status
import com.pranshulgg.recordmaster.utils.WaveformCache
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the recording alive when the app is in the background and shows a live notification
 * with Pause/Resume and Stop buttons. The recording itself is owned by [RecorderController].
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var statusJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        PreferencesHelper.init(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // Must go to the foreground right away, before the recorder is even built.
                startInForeground(buildNotification(Status.RECORDING))
                if (!RecorderController.isActive && !RecorderController.start(this)) {
                    stopForegroundAndSelf()
                    return START_NOT_STICKY
                }
                startObserving()
            }
            ACTION_PAUSE -> { RecorderController.pause(); refreshOrStop() }
            ACTION_RESUME -> { RecorderController.resume(); refreshOrStop() }
            ACTION_STOP -> {
                val path = RecorderController.stop()
                if (path != null) WaveformCache.warmUp(this, listOf(File(path)))
                stopForegroundAndSelf()
            }
            else -> if (!RecorderController.isActive) stopForegroundAndSelf()
        }
        return START_NOT_STICKY
    }

    private fun startObserving() {
        statusJob?.cancel()
        statusJob = scope.launch {
            RecorderController.status.collect { status ->
                if (status == Status.IDLE) stopForegroundAndSelf() else notify(buildNotification(status))
            }
        }
    }

    private fun refreshOrStop() {
        if (RecorderController.isActive) notify(buildNotification(RecorderController.status.value))
        else stopForegroundAndSelf()
    }

    private fun startInForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun stopForegroundAndSelf() {
        statusJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notify(n: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while a recording is in progress"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun servicePending(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code,
            Intent(this, RecordingService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun buildNotification(status: Status): Notification {
        val paused = status == Status.PAUSED
        val name = RecorderController.fileName.value?.removeSuffix(".m4a")
        val elapsed = RecorderController.elapsedMs()

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.fiber_manual_record_24px)
            .setContentTitle(if (paused) "Recording paused" else "Recording")
            .setContentText(name ?: "")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            // Android 16 "live update": shown as a status-bar chip / on the lock screen, and picked
            // up by apps that mirror live notifications.
            .addExtras(android.os.Bundle().apply {
                putBoolean("android.requestPromotedOngoing", true)
            })

        if (paused) {
            // The clock stops while paused, so show the frozen time instead of a running one.
            b.setShowWhen(false).setUsesChronometer(false)
                .setSubText(formatElapsed(elapsed))
            b.addAction(R.drawable.play_arrow_24px, "Resume", servicePending(ACTION_RESUME, 1))
        } else {
            // A live clock that ticks by itself, no updates needed every second.
            b.setShowWhen(true).setUsesChronometer(true)
                .setWhen(System.currentTimeMillis() - elapsed)
            b.addAction(R.drawable.pause_24px, "Pause", servicePending(ACTION_PAUSE, 2))
        }
        b.addAction(R.drawable.stop_24px, "Stop", servicePending(ACTION_STOP, 3))
        return b.build()
    }

    private fun formatElapsed(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%02d:%02d", m, sec)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 4101
        const val ACTION_START = "com.captechomar.recorder.action.START"
        const val ACTION_PAUSE = "com.captechomar.recorder.action.PAUSE"
        const val ACTION_RESUME = "com.captechomar.recorder.action.RESUME"
        const val ACTION_STOP = "com.captechomar.recorder.action.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingService::class.java).setAction(ACTION_START)
            )
        }

        fun send(context: Context, action: String) {
            context.startService(Intent(context, RecordingService::class.java).setAction(action))
        }
    }
}
