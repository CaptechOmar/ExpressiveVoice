package com.pranshulgg.recordmaster.helpers

import android.content.Context
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The one place that owns the microphone recording. It lives for the whole app process, so the
 * recording screen and the notification (via RecordingService) are two views of the same thing.
 */
object RecorderController {
    enum class Status { IDLE, RECORDING, PAUSED }

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _fileName = MutableStateFlow<String?>(null)
    /** Name of the file being recorded (e.g. "Rec_004.m4a"), or null when idle. */
    val fileName: StateFlow<String?> = _fileName.asStateFlow()

    /** Emits the saved file's path (or null if saving failed) every time a recording finishes. */
    private val _finished = MutableSharedFlow<String?>(extraBufferCapacity = 4)
    val finished: SharedFlow<String?> = _finished.asSharedFlow()

    /** Emits when a recording couldn't be started. */
    private val _startFailed = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val startFailed: SharedFlow<Unit> = _startFailed.asSharedFlow()

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var accumulatedMs = 0L
    private var segmentStart = 0L

    val isActive: Boolean get() = _status.value != Status.IDLE

    /** Recorded time so far, not counting time spent paused. */
    fun elapsedMs(): Long = synchronized(this) {
        accumulatedMs + if (_status.value == Status.RECORDING) SystemClock.elapsedRealtime() - segmentStart else 0L
    }

    /** Current loudness 0..32767 for the live waveform. */
    fun maxAmplitude(): Int = try { recorder?.maxAmplitude ?: 0 } catch (_: Exception) { 0 }

    fun start(context: Context): Boolean = synchronized(this) {
        if (isActive) return true
        try {
            val dir = StorageHelper.getRecordingsDir(context)
            // First unused number, so a new recording can never overwrite an existing one.
            var count = (dir.listFiles()?.size ?: 0) + 1
            var target: File
            do {
                target = File(dir, "Rec_${String.format(Locale.getDefault(), "%03d", count)}.m4a")
                count++
            } while (target.exists())

            fun build(quality: RecordQuality): MediaRecorder {
                val r = MediaRecorder()
                try {
                    r.apply {
                        setAudioSource(MediaRecorder.AudioSource.MIC)
                        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        setAudioSamplingRate(quality.sampleRate)
                        setAudioEncodingBitRate(quality.bitRate)
                        setOutputFile(target.absolutePath)
                        prepare()
                        start()
                    }
                    return r
                } catch (e: Exception) {
                    try { r.release() } catch (_: Exception) {}
                    throw e
                }
            }

            val chosen = RecordQuality.current()
            val rec = try {
                build(chosen)
            } catch (e: Exception) {
                // Some phones reject certain sample rates / bit rates: fall back to the safe default.
                if (chosen == RecordQuality.STANDARD) throw e
                Log.w(TAG, "quality ${chosen.key} not supported, using standard", e)
                build(RecordQuality.STANDARD)
            }
            try { rec.maxAmplitude } catch (_: Exception) {} // the first read is always 0

            recorder = rec
            file = target
            accumulatedMs = 0L
            segmentStart = SystemClock.elapsedRealtime()
            _fileName.value = target.name
            _status.value = Status.RECORDING
            true
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            recorder = null
            file = null
            _startFailed.tryEmit(Unit)
            false
        }
    }

    fun pause(): Boolean = synchronized(this) {
        if (_status.value != Status.RECORDING) return false
        try {
            recorder?.pause()
            accumulatedMs += SystemClock.elapsedRealtime() - segmentStart
            _status.value = Status.PAUSED
            true
        } catch (e: Exception) {
            Log.w(TAG, "pause failed", e)
            false
        }
    }

    fun resume(): Boolean = synchronized(this) {
        if (_status.value != Status.PAUSED) return false
        try {
            recorder?.resume()
            segmentStart = SystemClock.elapsedRealtime()
            _status.value = Status.RECORDING
            true
        } catch (e: Exception) {
            Log.w(TAG, "resume failed", e)
            false
        }
    }

    /** Stops and keeps the recording. Returns the saved file's path. */
    fun stop(): String? = synchronized(this) {
        if (!isActive) return null
        val path = finish(delete = false)
        _finished.tryEmit(path)
        path
    }

    /** Stops and throws the recording away. */
    fun discard() = synchronized(this) {
        if (isActive) finish(delete = true)
    }

    private fun finish(delete: Boolean): String? {
        val rec = recorder
        val out = file
        var ok = true
        try {
            rec?.let {
                try { it.stop() } catch (e: Exception) { ok = false; Log.w(TAG, "stop() threw", e) }
                try { it.reset() } catch (_: Exception) {}
                try { it.release() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "error stopping/releasing", e)
        }
        recorder = null
        file = null
        accumulatedMs = 0L
        _fileName.value = null
        _status.value = Status.IDLE
        if (delete) {
            out?.delete()
            return null
        }
        return out?.takeIf { it.exists() }?.absolutePath
    }

    private const val TAG = "RecorderController"
}
