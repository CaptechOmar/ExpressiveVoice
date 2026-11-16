package com.pranshulgg.recordmaster.helpers

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import androidx.core.content.ContextCompat
import com.pranshulgg.recordmaster.PlaybackService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/**
 * Owns the MediaPlayer for the recording being played, so the player screen and the media
 * notification (PlaybackService) control the very same playback. Call from the main thread.
 */
object PlaybackController {
    private val _player = MutableStateFlow<MediaPlayer?>(null)
    /** The prepared player, or null while loading / when nothing is loaded. */
    val player: StateFlow<MediaPlayer?> = _player.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _duration = MutableStateFlow(0)
    val duration: StateFlow<Int> = _duration.asStateFlow()

    private val _path = MutableStateFlow<String?>(null)
    /** Path of the loaded recording, or null when nothing is loaded. */
    val path: StateFlow<String?> = _path.asStateFlow()

    private val _version = MutableStateFlow(0)
    /** Changes whenever playback state changes in any way (play, pause, seek, load, stop). */
    val version: StateFlow<Int> = _version.asStateFlow()

    private val _completions = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val completions: SharedFlow<Unit> = _completions.asSharedFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private var appContext: Context? = null
    private var preparing = false
    private var playWhenPrepared = false
    private var pending: MediaPlayer? = null
    private var pendingSeek = 0

    private fun bump() = _version.update { it + 1 }

    /** Prepares [filePath] for playback. Does nothing if it's already loaded. */
    fun load(context: Context, filePath: String) {
        val ctx = context.applicationContext
        appContext = ctx
        if (_path.value == filePath && (_player.value != null || preparing)) return
        release()
        _path.value = filePath
        preparing = true
        try {
            val mp = MediaPlayer()
            pending = mp
            mp.setDataSource(ctx, Uri.fromFile(File(filePath)))
            mp.setOnPreparedListener { p ->
                preparing = false
                pending = null
                _duration.value = p.duration
                _player.value = p
                if (pendingSeek > 0) {
                    try { p.seekTo(pendingSeek) } catch (_: Exception) {}
                    pendingSeek = 0
                }
                if (playWhenPrepared) {
                    playWhenPrepared = false
                    startPlayback(p)
                }
                bump()
            }
            mp.setOnCompletionListener {
                _isPlaying.value = false
                _completions.tryEmit(Unit)
                bump()
            }
            mp.setOnErrorListener { _, what, extra ->
                preparing = false
                playWhenPrepared = false
                _isPlaying.value = false
                _errors.tryEmit("Playback error: what=$what extra=$extra")
                bump()
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            preparing = false
            playWhenPrepared = false
            _errors.tryEmit("Failed to play: ${e.message}")
        }
    }

    private fun startPlayback(p: MediaPlayer) {
        // A foreground service keeps playing (and shows the media notification) in the background.
        appContext?.let { ctx ->
            try {
                ContextCompat.startForegroundService(
                    ctx,
                    Intent(ctx, PlaybackService::class.java).setAction(PlaybackService.ACTION_SYNC)
                )
            } catch (_: Exception) {
                // No notification then, but playback itself still works.
            }
        }
        try { p.start() } catch (_: Exception) { return }
        _isPlaying.value = true
        bump()
    }

    fun toggle() {
        val p = _player.value
        if (p == null) {
            if (_path.value != null) playWhenPrepared = true
        } else if (p.isPlaying) pause() else startPlayback(p)
    }

    fun play() {
        val p = _player.value
        if (p == null) { if (_path.value != null) playWhenPrepared = true }
        else if (!p.isPlaying) startPlayback(p)
    }

    fun pause() {
        val p = _player.value ?: return
        try { if (p.isPlaying) p.pause() } catch (_: Exception) {}
        _isPlaying.value = false
        bump()
    }

    fun seekTo(ms: Int) {
        val max = _duration.value
        val target = if (max > 0) ms.coerceIn(0, max) else ms.coerceAtLeast(0)
        val p = _player.value
        if (p == null) pendingSeek = target
        else try { p.seekTo(target) } catch (_: Exception) {}
        bump()
    }

    fun seekBy(deltaMs: Int) = seekTo(position() + deltaMs)

    fun position(): Int = try { _player.value?.currentPosition ?: pendingSeek } catch (_: Exception) { 0 }

    /** Stops playback and unloads the recording (also removes the media notification). */
    fun stop() {
        release()
        _path.value = null
        bump()
    }

    private fun release() {
        playWhenPrepared = false
        preparing = false
        pendingSeek = 0
        try { pending?.release() } catch (_: Exception) {}
        pending = null
        _player.value?.let { p ->
            try { p.stop() } catch (_: Exception) {}
            try { p.release() } catch (_: Exception) {}
        }
        _player.value = null
        _isPlaying.value = false
        _duration.value = 0
    }
}
