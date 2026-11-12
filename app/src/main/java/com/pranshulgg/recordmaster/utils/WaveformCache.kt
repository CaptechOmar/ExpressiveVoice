package com.pranshulgg.recordmaster.utils

import android.content.Context
import com.pranshulgg.recordmaster.screens.computePeaksFromAudioFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Remembers the waveform of every recording so the player doesn't have to decode the audio each
 * time it opens: an in-memory copy for instant access, a tiny file on disk for the next launch.
 *
 * Entries are keyed by path + modified time + size, so editing or replacing a file invalidates it.
 * Work runs in an app-wide scope, so leaving the player never throws away a half-finished decode,
 * and asking for the same file twice shares one decode.
 */
object WaveformCache {
    const val BAR_COUNT = 140
    private const val MAX_IN_MEMORY = 48

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val memory = object : LinkedHashMap<String, FloatArray>(MAX_IN_MEMORY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) =
            size > MAX_IN_MEMORY
    }
    private val inFlight = HashMap<String, Deferred<FloatArray>>()

    private fun key(file: File) = "${file.absolutePath}|${file.lastModified()}|${file.length()}"

    private fun diskFile(context: Context, key: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(24)
        val dir = File(context.cacheDir, "waveforms").apply { if (!exists()) mkdirs() }
        return File(dir, "${hash}_$BAR_COUNT.bin")
    }

    /** The waveform if it's already in memory (no disk access, safe to call while composing). */
    fun peek(file: File): FloatArray? = synchronized(lock) { memory[key(file)] }

    /** True if this recording's waveform is already available in memory or on disk. */
    fun isCached(context: Context, file: File): Boolean {
        val k = key(file)
        return synchronized(lock) { memory.containsKey(k) } || diskFile(context, k).exists()
    }

    /** Returns the waveform, from the cache if possible, otherwise decoding the file. */
    suspend fun get(context: Context, file: File): FloatArray {
        val k = key(file)
        synchronized(lock) { memory[k] }?.let { return it }

        val appContext = context.applicationContext
        val job = synchronized(lock) {
            inFlight.getOrPut(k) {
                scope.async {
                    try {
                        val peaks = readDisk(diskFile(appContext, k))
                            ?: computePeaksFromAudioFile(file.absolutePath, BAR_COUNT)
                                .also { writeDisk(diskFile(appContext, k), it) }
                        synchronized(lock) { memory[k] = peaks }
                        peaks
                    } finally {
                        synchronized(lock) { inFlight.remove(k) }
                    }
                }
            }
        }
        return job.await()
    }

    /**
     * Quietly prepares waveforms for [files] in the background, one at a time, so they open
     * instantly later. Skips files that are already cached.
     */
    fun warmUp(context: Context, files: List<File>) {
        val appContext = context.applicationContext
        scope.launch {
            delay(1_000) // let the screen settle first
            for (file in files) {
                try {
                    if (!isCached(appContext, file)) get(appContext, file)
                } catch (_: Exception) {
                    // A file that can't be decoded just won't get a cached waveform.
                }
            }
        }
    }

    private suspend fun readDisk(file: File): FloatArray? = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) return@withContext null
            DataInputStream(FileInputStream(file).buffered()).use { input ->
                val size = input.readInt()
                if (size != BAR_COUNT) return@use null
                FloatArray(size) { input.readFloat() }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeDisk(file: File, peaks: FloatArray) {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            DataOutputStream(FileOutputStream(tmp).buffered()).use { out ->
                out.writeInt(peaks.size)
                peaks.forEach { out.writeFloat(it) }
            }
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (_: Exception) {
        }
    }
}
