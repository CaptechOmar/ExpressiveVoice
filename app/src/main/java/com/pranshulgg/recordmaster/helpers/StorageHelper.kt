package com.pranshulgg.recordmaster.helpers

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Resolves where recordings live.
 *
 * By default recordings are stored in the app's own external "Music" folder (no permission needed).
 * The user can pick any other folder in Settings; because the rest of the app works with plain
 * [File] paths, a custom folder requires "All files access" (API 30+) or the legacy storage
 * permission (API 24-29).
 */
object StorageHelper {

    const val KEY_RECORDINGS_DIR = "recordings_dir"

    fun defaultRecordingsDir(context: Context): File =
        context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir

    /** The custom folder the user chose, or null when the default is used. */
    fun customRecordingsPath(): String? =
        PreferencesHelper.getString(KEY_RECORDINGS_DIR)?.takeIf { it.isNotBlank() }

    /**
     * The folder recordings should be read from / written to. Falls back to the default folder if
     * the custom one can't be used (permission revoked, SD card removed, folder deleted...).
     */
    fun getRecordingsDir(context: Context): File {
        val custom = customRecordingsPath()?.let { File(it) }
        if (custom != null && hasStorageAccess(context)) {
            if (!custom.exists()) custom.mkdirs()
            if (custom.isDirectory && custom.canWrite()) return custom
        }
        return defaultRecordingsDir(context).also { if (!it.exists()) it.mkdirs() }
    }

    fun setCustomRecordingsPath(path: String?) {
        if (path == null) PreferencesHelper.remove(KEY_RECORDINGS_DIR)
        else PreferencesHelper.setString(KEY_RECORDINGS_DIR, path)
    }

    // -------------------- Permission --------------------

    fun hasStorageAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

    /** Intent that opens the system screen where the user grants "All files access" (API 30+). */
    @RequiresApi(Build.VERSION_CODES.R)
    fun allFilesAccessIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )

    // -------------------- Folder picker result -> path --------------------

    /**
     * Converts the tree URI returned by `ACTION_OPEN_DOCUMENT_TREE` into a real file path.
     * Returns null for providers that aren't backed by a local path (cloud drives etc.).
     */
    fun treeUriToPath(treeUri: Uri): String? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val split = docId.split(":", limit = 2)
            val volume = split[0]
            val relative = split.getOrNull(1).orEmpty()

            val root = when {
                volume.equals("primary", ignoreCase = true) ->
                    Environment.getExternalStorageDirectory()
                volume.equals("home", ignoreCase = true) ->
                    File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOCUMENTS)
                volume.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) ->
                    File("/storage/$volume")
                else -> return null
            }
            if (relative.isEmpty()) root.absolutePath else File(root, relative).absolutePath
        } catch (_: Exception) {
            null
        }
    }

    // -------------------- Moving existing recordings --------------------

    /** True if [child] is [parent] itself or lives somewhere inside it. */
    fun isSameOrInside(child: File, parent: File): Boolean {
        val c = child.canonicalFile
        val p = parent.canonicalFile
        return c == p || c.path.startsWith(p.path + File.separator)
    }

    /**
     * Moves everything (recordings, sub-folders, the "garbage" folder) from [from] into [to].
     * Returns the number of items that could not be moved. Call from a background thread.
     */
    fun moveAllRecordings(from: File, to: File): Int {
        if (!to.exists()) to.mkdirs()
        var failed = 0
        for (item in from.listFiles() ?: emptyArray()) {
            val target = File(to, item.name)
            try {
                if (target.exists() && item.isDirectory && target.isDirectory) {
                    // Merge folders that exist on both sides (e.g. "garbage").
                    failed += moveAllRecordings(item, target)
                    item.delete()
                } else if (!target.exists()) {
                    if (!item.renameTo(target)) {
                        if (item.isDirectory) item.copyRecursively(target) else item.copyTo(target)
                        if (!item.deleteRecursively()) failed++
                    }
                } else {
                    failed++ // name clash with an existing file: leave the original untouched
                }
            } catch (_: Exception) {
                failed++
            }
        }
        return failed
    }
}
