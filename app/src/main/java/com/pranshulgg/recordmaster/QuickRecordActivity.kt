package com.pranshulgg.recordmaster

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.pranshulgg.recordmaster.helpers.PreferencesHelper
import com.pranshulgg.recordmaster.helpers.RecorderController

/**
 * Starts a recording immediately, then shows the recording screen.
 *
 * It's the target of the long-press "Record" shortcut and is exported with its own label, so
 * Shortcut Maker, KWGT, Tasker and similar apps can launch it too
 * (action `com.captechomar.recorder.action.QUICK_RECORD`).
 */
class QuickRecordActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (micGranted()) startAndOpen() else {
                Toast.makeText(this, "Mic permission is required to record", Toast.LENGTH_LONG)
                    .show()
                finish()
            }
        }

    private fun micGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        PreferencesHelper.init(this)
        super.onCreate(savedInstanceState)

        val needed = mutableListOf<String>()
        if (!micGranted()) needed += Manifest.permission.RECORD_AUDIO
        // Same one-time notification prompt as the Record screen.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED &&
            PreferencesHelper.getBool("asked_notification_permission") != true
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
            PreferencesHelper.setBool("asked_notification_permission", true)
        }

        if (savedInstanceState != null) return // waiting on the permission dialog already
        if (needed.isEmpty()) startAndOpen() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun startAndOpen() {
        if (!RecorderController.isActive) RecordingService.start(this)
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_RECORDING, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }
}
