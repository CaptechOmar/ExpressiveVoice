package com.pranshulgg.recordmaster.utils

import android.media.MediaPlayer
import androidx.compose.runtime.MutableState
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A month header plus the recordings that belong under it. */
data class RecordingGroup(val month: String, val files: List<File>)

/**
 * Files directly inside [dir], newest first. Each file's timestamp is read exactly once
 * (sorting by `lastModified()` directly hits the filesystem on every comparison).
 */
fun listRecordingFiles(dir: File): List<Pair<File, Long>> =
    (dir.listFiles() ?: emptyArray())
        .filter { it.isFile }
        .map { it to it.lastModified() }
        .sortedByDescending { it.second }

/** Groups a newest-first list by month name, keeping the newest-first order. */
fun groupByMonth(items: List<Pair<File, Long>>): List<RecordingGroup> {
    val sdfMonth = SimpleDateFormat("MMMM", Locale.getDefault())
    return items
        .groupBy({ sdfMonth.format(Date(it.second)) }, { it.first })
        .map { (month, files) -> RecordingGroup(month, files) }
}

 fun moveFileToDir(file: File, targetDir: File) {
    try {
        val target = File(targetDir, file.name)
        if (!file.renameTo(target)) {
            file.copyTo(target, overwrite = true)
            file.delete()
        }
    } catch (_: Exception) { }
}

 fun stopIfPlayingAndCleanup(file: File, mediaPlayerState: MutableState<MediaPlayer?>, playingPath: String?) {
    val mp = mediaPlayerState.value
    if (playingPath == file.absolutePath) {
        try { mp?.stop() } catch (_: Exception) {}
        try { mp?.release() } catch (_: Exception) {}
        mediaPlayerState.value = null
    }
}

/** Remembers recording durations so scrolling the list doesn't re-open every file. */
object DurationCache {
    private val cache = ConcurrentHashMap<String, Int>()

    private fun key(path: String, modified: Long) = "$path|$modified"

    fun get(path: String, modified: Long): Int? = cache[key(path, modified)]

    /** Reads the duration (ms) on a background thread; 0 if it can't be read. */
    suspend fun load(path: String, modified: Long): Int {
        get(path, modified)?.let { return it }
        val duration = withContext(Dispatchers.IO) {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(path)
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
            } catch (_: Exception) {
                0
            } finally {
                try { mmr.release() } catch (_: Exception) {}
            }
        }
        cache[key(path, modified)] = duration
        return duration
    }
}
