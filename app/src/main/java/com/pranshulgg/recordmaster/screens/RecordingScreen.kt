package com.pranshulgg.recordmaster.screens

import android.Manifest
import android.os.Environment
import android.util.Log
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.with
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.activity.compose.BackHandler
import com.pranshulgg.recordmaster.RecordingService
import com.pranshulgg.recordmaster.helpers.PreferencesHelper
import com.pranshulgg.recordmaster.helpers.RecorderController
import com.pranshulgg.recordmaster.helpers.SnackbarManager
import com.pranshulgg.recordmaster.utils.WaveformCache
import com.pranshulgg.recordmaster.ui.components.HapticOnPress
import com.pranshulgg.recordmaster.ui.components.Symbol
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.pranshulgg.recordmaster.R
import com.pranshulgg.recordmaster.ui.components.ConfirmDialog


@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalAnimationApi::class
)
@Composable
fun RecordingScreen(onDone: () -> Unit, autoStart: Boolean = false) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val status by RecorderController.status.collectAsState()
    val isRecording = status != RecorderController.Status.IDLE
    val isPaused = status == RecorderController.Status.PAUSED
    var elapsedSeconds by remember { mutableStateOf(0L) }

    val fileName by RecorderController.fileName.collectAsState()
    var shownTitle by remember { mutableStateOf("Record") }
    LaunchedEffect(fileName) { fileName?.let { shownTitle = it.removeSuffix(".m4a") } }

    val amplitudes = remember { mutableStateListOf<Float>() }
    val maxSamples = 40

    var showConfirmDeleteDialog by remember { mutableStateOf(false) }

    // Recording itself runs in a foreground service (with the live notification), so it keeps
    // going if the app goes to the background.
    fun doStartRecording() {
        if (!RecorderController.isActive) RecordingService.start(context)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { results ->
            if (results[Manifest.permission.RECORD_AUDIO] != false &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                doStartRecording()
            } else {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("Mic permission is required to record")
                }
            }
        }
    )

    /** Starts recording, asking for the microphone (and notification) permission first if needed. */
    fun beginRecording() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.RECORD_AUDIO
        // Needed to show the recording notification; asked once, recording works without it.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED &&
            !(PreferencesHelper.getBool("asked_notification_permission") == true)
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
            PreferencesHelper.setBool("asked_notification_permission", true)
        }
        if (needed.isEmpty()) doStartRecording()
        else permissionLauncher.launch(needed.toTypedArray())
    }

    // Opened from the big red button: start right away instead of waiting for a second tap.
    // (Saved so rotating the phone or coming back to this screen doesn't start another recording.)
    var autoStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (autoStart && !autoStarted && !RecorderController.isActive) {
            autoStarted = true
            beginRecording()
        }
    }

    // The recording was stopped (here, or from the notification): confirm and go back to the list.
    LaunchedEffect(Unit) {
        RecorderController.finished.collect { path ->
            if (path != null) WaveformCache.warmUp(context, listOf(File(path)))
            SnackbarManager.showMessage(
                if (path != null) "Saved: ${File(path).name}" else "Save failed"
            )
            onDone()
        }
    }
    LaunchedEffect(Unit) {
        RecorderController.startFailed.collect {
            snackbarHostState.showSnackbar("Could not start recorder")
        }
    }

    // Leaving while recording saves it, like Stop.
    BackHandler(enabled = isRecording) { RecorderController.stop() }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            if (amplitudes.isEmpty()) repeat(maxSamples) { amplitudes.add(0f) }
        } else {
            amplitudes.clear()
            elapsedSeconds = 0L
        }
    }

    LaunchedEffect(isRecording, isPaused) {
        while (isRecording) {
            elapsedSeconds = RecorderController.elapsedMs() / 1000
            delay(250)
        }
    }

    LaunchedEffect(isRecording, isPaused) {
        if (isRecording && !isPaused) {
            RecorderController.maxAmplitude() // the first read is always 0
            var prev = amplitudes.lastOrNull() ?: 0f

            while (isRecording && !isPaused) {
                val normalized = (RecorderController.maxAmplitude() / 32767f).coerceIn(0f, 1f)
                val smoothed = prev * 0.85f + normalized * 0.15f
                amplitudes.add(smoothed)
                if (amplitudes.size > maxSamples) amplitudes.removeAt(0)
                prev = smoothed
                delay(60) // 16-20 samples per second
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {Text(shownTitle)},
                subtitle = {Text(SimpleDateFormat("MMM dd 'at' h:mm a", Locale.getDefault()).format(Date()))},
                navigationIcon = {
                    IconButton(
                        onClick = { if (isRecording) RecorderController.stop() else onDone() }
                    ) {
                        Symbol(R.drawable.arrow_back_24px, color = MaterialTheme.colorScheme.onSurface)
                    }
                },
                actions = {
                    IconButton(shapes = IconButtonDefaults.shapes(), onClick = {
                        showConfirmDeleteDialog = true
                    }) {
                        Symbol(R.drawable.delete_forever_24px)
                    }

                    if (showConfirmDeleteDialog) {
                        ConfirmDialog(
                            title = "Delete recording",
                            message = "This recording will be permanently deleted. This cannot be undone",
                            confirmText = "Delete",
                            cancelText = "Cancel",
                            onConfirm = {
                                RecorderController.discard()
                                onDone()
                            },
                            onDismiss = { showConfirmDeleteDialog = false }
                        )
                    }
                }
            )
        }


    ) { padding ->
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {



                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(fraction = 0.7f)
                        .clip(MaterialTheme.shapes.extraLarge)
                        .background(MaterialTheme.colorScheme.surfaceContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Waveform(amplitudes = amplitudes)
                }


                Spacer(modifier = Modifier.height(15.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(
                                if (isRecording && !isPaused) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = formatElapsed(elapsedSeconds), fontSize = 40.sp, color = MaterialTheme.colorScheme.secondary)
                }
                Spacer(modifier = Modifier.height(24.dp))


                Row(
                    modifier = Modifier.fillMaxWidth().animateContentSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (!isRecording) Arrangement.Center else Arrangement.spacedBy(5.dp)
                ) {


                    val buttonLabel = when {
                        !isRecording -> "Record"
                        isRecording && !isPaused -> "Pause"
                        else -> "Resume"
                    }


                    Box(
                       modifier = if (!isRecording) Modifier.fillMaxWidth() else Modifier.weight(1f),
                        contentAlignment = Alignment.Center

                    ) {
                                ToggleButton(
                                    checked = isRecording && !isPaused,
                                    colors = ToggleButtonDefaults.toggleButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        checkedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                    ),
                                    onCheckedChange = {
                                        when {
                                            !isRecording -> beginRecording()
                                            !isPaused -> if (!RecorderController.pause()) {
                                                coroutineScope.launch {
                                                    snackbarHostState.showSnackbar("Pause failed on this device")
                                                }
                                            }
                                            else -> if (!RecorderController.resume()) {
                                                coroutineScope.launch {
                                                    snackbarHostState.showSnackbar("Resume failed on this device")
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.heightIn(ButtonDefaults.LargeContainerHeight).fillMaxWidth(if (!isRecording) 0.6f else 1f),
                                    contentPadding = if (!isRecording)  ButtonDefaults.contentPaddingFor(ButtonDefaults.LargeContainerHeight) else PaddingValues(0.dp),

                                    shapes = ToggleButtonDefaults.shapes(),
                                ) {
                                    val iconRes = when {
                                        !isRecording -> R.drawable.fiber_manual_record_24px
                                        isPaused -> R.drawable.play_arrow_24px
                                        else -> R.drawable.pause_24px
                                    }

                                    val contentColor = if(isRecording && !isPaused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimaryContainer

                                    Symbol(iconRes, size = 28.dp, color = contentColor,)
                                    Spacer(Modifier.width(5.dp))
                                    Text(buttonLabel, color = contentColor, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                }
                    }

                    val stopVisible = isRecording

                    val stopAlpha by animateFloatAsState(if (stopVisible) 1f else 0f)
                    val stopWeight by animateFloatAsState(if (stopVisible) 1f else 0.001f)

                    Box(
                        modifier = Modifier
                            .weight(stopWeight)
                            .alpha(stopAlpha)
                    ) {
                                val stopInteraction = remember { MutableInteractionSource() }
                                HapticOnPress(stopInteraction)
                                Button(
                                    interactionSource = stopInteraction,
                                    onClick = { RecorderController.stop() },
                                    modifier = Modifier.fillMaxWidth().heightIn(ButtonDefaults.LargeContainerHeight),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer
                                    )

                                ) {
                                    Symbol(R.drawable.stop_24px, size = 28.dp, color = MaterialTheme.colorScheme.onErrorContainer)
                                    Spacer(Modifier.width(5.dp))
                                    Text("Stop", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                                }
                    }

                }

            }
                    Spacer(modifier = Modifier.height(16.dp))

//                Button(onClick = { onDone() }) {
//                    Text("Done")
                }
}
}

@Composable
private fun Waveform(amplitudes: List<Float>) {
    val display = remember { mutableStateListOf<Float>() }
    val colorBar = MaterialTheme.colorScheme.primary

    LaunchedEffect(amplitudes.size) {
        if (display.size < amplitudes.size) {
            repeat(amplitudes.size - display.size) { display.add(0f) }
        } else if (display.size > amplitudes.size) {
            for (i in display.size - 1 downTo amplitudes.size) display.removeAt(i)
        }
    }

    // `amplitudes` is the same state list for the whole screen's lifetime, so this effect starts
    // once (it used to restart every ~60 ms because a fresh copy of the list was passed in).
    LaunchedEffect(amplitudes) {
        val activeDelay = 16L // ~60 fps while bars are moving
        val idleDelay = 100L  // nothing to animate: just check occasionally
        val followFactor = 0.15f

        while (isActive) {
            val target = amplitudes
            if (display.size < target.size) {
                repeat(target.size - display.size) { display.add(0f) }
            }

            var changed = false
            for (i in target.indices) {
                val curr = display.getOrNull(i) ?: 0f
                val next = curr * (1 - followFactor) + target[i] * followFactor
                // Skip writes that wouldn't visibly change anything so we don't invalidate the canvas.
                if (kotlin.math.abs(next - curr) > 0.0005f) {
                    if (i < display.size) display[i] = next else display.add(next)
                    changed = true
                }
            }

            delay(if (changed) activeDelay else idleDelay)
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val count = display.size.coerceAtLeast(1)
        val barWidth = (w / count).coerceAtLeast(2f)
        val gap = (barWidth * 0.2f).coerceAtMost(6f)
        val actualBar = barWidth - gap

        for (i in 0 until count) {
            val amp = display.getOrNull(i) ?: 0f
            val scale = 3f
            val barHeight = (amp * scale).coerceIn(0f, 1f) * h

            val left = i * barWidth + gap / 2f
            val top = (h - barHeight) / 2f
            drawRoundRect(
                color = colorBar,
                topLeft = Offset(left, top),
                size = Size(actualBar, barHeight),
                cornerRadius = CornerRadius(x = actualBar / 2f, y = actualBar / 2f)
            )
        }
    }
}

private fun formatElapsed(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format("%02d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
}
