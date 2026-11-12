package com.pranshulgg.recordmaster.screens

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.motionScheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import com.pranshulgg.recordmaster.R
import com.pranshulgg.recordmaster.ui.components.ConfirmDialog
import com.pranshulgg.recordmaster.ui.components.DrawerContent
import com.pranshulgg.recordmaster.ui.components.EmptyContainerPlaceholder
import com.pranshulgg.recordmaster.ui.components.RecordButton
import com.pranshulgg.recordmaster.ui.components.RecordingRow
import com.pranshulgg.recordmaster.ui.components.Symbol
import com.pranshulgg.recordmaster.ui.components.TopBarWithSearch
import com.pranshulgg.recordmaster.helpers.StorageHelper
import com.pranshulgg.recordmaster.utils.RecordingGroup
import com.pranshulgg.recordmaster.utils.WaveformCache
import com.pranshulgg.recordmaster.utils.groupByMonth
import com.pranshulgg.recordmaster.utils.listRecordingFiles
import com.pranshulgg.recordmaster.utils.moveFileToDir
import com.pranshulgg.recordmaster.utils.stopIfPlayingAndCleanup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(navController: NavController, snackbarHostState: SnackbarHostState){
    val context = LocalContext.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var currentTab by remember { mutableStateOf("home") }
    val scope = rememberCoroutineScope()
    var selectedFolderName by remember { mutableStateOf<String?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    val mediaPlayer = remember { mutableStateOf<MediaPlayer?>(null) }
    var playingPath by remember { mutableStateOf<String?>(null) }

    var selectedPaths by remember { mutableStateOf(setOf<String>()) }

    BackHandler(enabled = showSearch) {
        searchQuery = ""
        showSearch = false
    }

    BackHandler(enabled = selectedPaths.isNotEmpty()) {
        selectedPaths = emptySet()
    }

    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by rememberSaveable { mutableStateOf("") }
    var foldersExpanded by remember { mutableStateOf(true) }

    var showDeleteFolderDialog by remember { mutableStateOf(false) }
    var folderToDeleteName by remember { mutableStateOf<String?>(null) }
    var selectedFolderKey by remember { mutableStateOf(0L) }

    val showMessage: suspend (String) -> Unit = { msg ->
        if (snackbarHostState != null) {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(msg)
        } else {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val musicDir = remember { StorageHelper.getRecordingsDir(context) }

    val garbageDir = remember(musicDir) {
        File(musicDir, "garbage").apply { if (!exists()) mkdirs() }
    }

    var rootDirKey by remember { mutableStateOf(0L) }
    var garbageDirKey by remember { mutableStateOf(0L) }


    fun toggleSelection(path: String) {
        selectedPaths = if (selectedPaths.contains(path)) selectedPaths - path else selectedPaths + path
    }
    var showGarbageConfirmDeleteSelectedDialog by remember { mutableStateOf(false) }
    var garbageDeleteTarget by remember { mutableStateOf<File?>(null) }


    fun shareSelected() {
        if (selectedPaths.isEmpty()) return
        try {
            val uris = ArrayList<Uri>()
            selectedPaths.forEach { path ->
                val f = File(path)
                val uri: Uri = try {
                    FileProvider.getUriForFile(context, "${context.packageName}.provider", f)
                } catch (_: Exception) {
                    Uri.fromFile(f)
                }
                uris.add(uri)
            }

            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = "audio/*"
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "audio/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                }
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(intent, "Share audio"))
        } catch (e: Exception) {
            scope.launch {
                if (snackbarHostState != null) snackbarHostState.showSnackbar("Failed to share files")
                else Toast.makeText(context, "Failed to share files", Toast.LENGTH_SHORT).show()
            }
        } finally {
            selectedPaths = emptySet()
        }
    }

    var filesToDelete: List<File> by remember { mutableStateOf(emptyList()) }

    fun deleteSelected() {
        if (selectedPaths.isEmpty()) return

        val files = selectedPaths.map { File(it) }
        files.forEach { file ->
            stopIfPlayingAndCleanup(file, mediaPlayer, playingPath)
        }

        if (currentTab == "garbage") {
            filesToDelete = files
            showGarbageConfirmDeleteSelectedDialog = true
        } else {
            files.forEach { file ->
                try { moveFileToDir(file, garbageDir) } catch (_: Throwable) {}
            }
            scope.launch { showMessage("Moved to garbage") }
        }

        rootDirKey++
        garbageDirKey++
        if (currentTab == "folder" && selectedFolderName != null) {
            selectedFolderKey++
        }

        selectedPaths = emptySet()
    }

    if (showGarbageConfirmDeleteSelectedDialog) {
        ConfirmDialog(
            title = "Delete recordings",
            message = "The selected recording(s) will be permanently deleted and cannot be undone",
            confirmText = "Delete",
            cancelText = "Cancel",
            onConfirm = {
                filesToDelete.forEach { file ->
                    try { file.delete() } catch (_: Throwable) {}
                }
                filesToDelete = emptyList()
                garbageDirKey++
                showGarbageConfirmDeleteSelectedDialog = false
            },
            onDismiss = { showGarbageConfirmDeleteSelectedDialog = false }
        )
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    ModalNavigationDrawer(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        drawerState = drawerState,
        drawerContent = {
            DrawerContent(
                currentTab = currentTab,
                selectedFolderName = selectedFolderName,
                rootDirKey = rootDirKey,
                musicDir = musicDir,
                onSelectTab = { tab, folder ->
                    currentTab = tab
                    selectedFolderName = folder
                    scope.launch { drawerState.close() }
                },
                onRequestCreateFolder = { showCreateFolderDialog = true },
                onRequestDeleteFolder = { name ->
                    folderToDeleteName = name
                    showDeleteFolderDialog = true
                },
                onfoldersExpanded = { value ->
                    foldersExpanded = value
                },
                foldersExpanded = foldersExpanded,
                navController = navController,
                closeDrawer = {
                    scope.launch { drawerState.close() }
                }
            )
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            floatingActionButtonPosition = FabPosition.Center,
            floatingActionButton = {
                AnimatedVisibility(
                    visible =  currentTab == "home" && !showSearch,
                    enter = scaleIn(
                        initialScale = 0.8f,
                        animationSpec = motionScheme.defaultSpatialSpec()
                    ) + fadeIn(),
                    exit = scaleOut(
                        targetScale = 0.8f,
                        animationSpec = motionScheme.defaultSpatialSpec()
                    ) + fadeOut(),
                ) {
                    RecordButton(
                        onClick = {
                            // launchSingleTop: a double tap must not open (and start) two recorders
                            navController.navigate("record?autostart=true") { launchSingleTop = true }
                        }
                    )
                }
            },
            topBar = {
                TopBarWithSearch(
                    showSearch = showSearch,
                    searchQuery = searchQuery,
                    onSearchChange = { searchQuery = it },
                    onToggleSearch = { showSearch = !showSearch },
                    openDrawer = { scope.launch { drawerState.open() } },
                    currentTab = currentTab,
                    selectedFolderName = selectedFolderName,
                    clearSearchQuery = {searchQuery = ""},
                    onRequestDeleteFolder = { name ->
                        folderToDeleteName = name
                        showDeleteFolderDialog = true
                    },
                    onBack = {
                        searchQuery = ""
                        showSearch = false
                    },
                    scrollBehavior = scrollBehavior,
                    isSelecting = selectedPaths.isNotEmpty(),
                    onShareSelected = {
                        shareSelected()
                    },
                    onDeleteSelected = {
                        deleteSelected()

                    },
                    onCloseSelection = {
                        selectedPaths = emptySet()
                    },
                    selectedCount = selectedPaths.size.toString()
                )

            }

        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .padding(top = innerPadding.calculateTopPadding())
                    .fillMaxSize()
            ) {



                var isPlaying by remember { mutableStateOf(false) }
                var currentPos by remember { mutableStateOf(0) }
                var duration by remember { mutableStateOf(0) }

                LaunchedEffect(isPlaying, mediaPlayer.value) {
                    while (isPlaying && mediaPlayer.value != null) {
                        try { currentPos = mediaPlayer.value?.currentPosition ?: 0 } catch (_: Exception) {}
                        delay(200)
                    }
                }

                // Everything below runs off the main thread: listing, sorting, searching and
                // month-grouping happen once per change instead of on every recomposition.
                val query = searchQuery
                val tab = currentTab
                val folderName = selectedFolderName
                val groupsState = produceState<List<RecordingGroup>?>(
                    null,
                    tab, folderName, query, rootDirKey, garbageDirKey, selectedFolderKey
                ) {
                    if (query.isNotBlank()) delay(150) // debounce typing
                    value = withContext(Dispatchers.IO) {
                        val items = if (query.isBlank()) {
                            when (tab) {
                                "garbage" -> listRecordingFiles(garbageDir)
                                "folder" ->
                                    if (!folderName.isNullOrEmpty()) listRecordingFiles(File(musicDir, folderName))
                                    else emptyList()
                                else -> listRecordingFiles(musicDir)
                            }
                        } else {
                            val subfolderFiles = (musicDir.listFiles() ?: emptyArray())
                                .filter { it.isDirectory && it.name != "garbage" }
                                .flatMap { listRecordingFiles(it) }
                            (listRecordingFiles(musicDir) + listRecordingFiles(garbageDir) + subfolderFiles)
                                .filter { it.first.name.contains(query, ignoreCase = true) }
                                .sortedByDescending { it.second }
                        }
                        groupByMonth(items)
                    }
                }
                val groups = groupsState.value

                // Quietly prepare the newest recordings' waveforms so they open instantly.
                LaunchedEffect(groups) {
                    val newest = groups?.flatMap { it.files }?.take(6)
                    if (!newest.isNullOrEmpty()) WaveformCache.warmUp(context, newest)
                }

                if (groups == null) {
                    // first load still in progress
                } else if (groups.isEmpty()) {
                    EmptyContainerPlaceholder(R.drawable.graphic_eq_24px, "No recordings")
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        groups.forEach { group ->
                            item(key = "month_${group.month}") {
                                    Text(
                                        text = group.month,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.W700,
                                        fontSize = 16.sp

                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                            }

                            items(group.files, key = { it.absolutePath }) { file ->
                                // Only the row that is playing gets the live position, so the other
                                // rows aren't recomposed 5 times a second during playback.
                                val isThisFile = playingPath == file.absolutePath
                                RecordingRow(
                                    file = file,
                                    isThisPlaying = isThisFile && isPlaying,
                                    progress = if (isThisFile && duration > 0) (currentPos.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f,
                                    currentPositionMillis = if (isThisFile) currentPos else 0,
                                    currentDurationMillis = if (isThisFile) duration else 0,
                                    onItemClick = {
                                        if (selectedPaths.isNotEmpty()) {
                                            toggleSelection(file.absolutePath)
                                        } else {
                                            val encoded = Uri.encode(file.absolutePath)
                                            navController.navigate("play/$encoded")
                                        }
                                    },
                                    onLongClick = {
                                        toggleSelection(file.absolutePath)
                                    },
                                    onPlayPause = {
                                        if (playingPath == file.absolutePath) {
                                            val mp = mediaPlayer.value
                                            if (mp != null && mp.isPlaying) {
                                                mp.pause(); isPlaying = false
                                            } else if (mp != null) {
                                                mp.start(); isPlaying = true
                                            }
                                        } else {
                                            try {
                                                mediaPlayer.value?.stop()
                                                mediaPlayer.value?.release()
                                            } catch (_: Exception) {}
                                            mediaPlayer.value = null
                                            try {
                                                val mp = MediaPlayer().apply {
                                                    setDataSource(file.absolutePath)
                                                    prepare()
                                                    start()
                                                }
                                                mediaPlayer.value = mp
                                                playingPath = file.absolutePath
                                                isPlaying = true
                                                duration = mp.duration
                                            } catch (e: Exception) {
                                                isPlaying = false
                                                playingPath = null
                                                mediaPlayer.value?.release()
                                                mediaPlayer.value = null
                                            }
                                        }
                                    },

                                    isGarbage = currentTab == "garbage",
                                    musicDir = musicDir,
                                    onDelete = {
                                        if (currentTab == "garbage") {

                                            garbageDeleteTarget = file

                                        } else {
                                            val fromParent = file.parentFile
                                            moveFileToDir(file, garbageDir)
                                            if (playingPath == file.absolutePath) playingPath = null
                                            rootDirKey++
                                            garbageDirKey++
                                            if (currentTab == "folder" && fromParent?.name == selectedFolderName) {
                                                selectedFolderKey++
                                            }
                                            scope.launch { showMessage("Moved to garbage") }

                                        }
                                        selectedPaths = selectedPaths - file.absolutePath
                                    },

                                    isSelected = selectedPaths.contains(file.absolutePath),

                                    onRestore = {
                                        val fromParent = file.parentFile
                                        moveFileToDir(file, musicDir)
                                        if (playingPath == file.absolutePath) playingPath = null
                                        rootDirKey++
                                        garbageDirKey++

                                        if (currentTab == "folder" && fromParent?.name == selectedFolderName) {
                                            selectedFolderKey++
                                        }
                                    },

                                    onMoved = {
                                        rootDirKey++
                                        garbageDirKey++
                                        selectedFolderName?.let {
                                            selectedFolderKey++
                                        }
                                    },

                                    onRenamed = {
                                        rootDirKey++
                                        garbageDirKey++
                                        selectedFolderName?.let {
                                            selectedFolderKey++
                                        }
                                    },
                                    onShowMessage = { message ->
                                        scope.launch {
                                            showMessage(message)
                                        }
                                    },
                                    currentTab = currentTab

                                )

                            }
                        }

                        item(key = "bottom_spacer") {
                            Spacer(modifier = Modifier.height(140.dp))
                        }
                    }
                }

                garbageDeleteTarget?.let { target ->
                    ConfirmDialog(
                        title = "Delete recording",
                        message = "The recording will be permanently deleted and cannot be undone",
                        confirmText = "Delete",
                        cancelText = "Cancel",
                        onConfirm = {
                            stopIfPlayingAndCleanup(target, mediaPlayer, playingPath)
                            target.delete()
                            garbageDirKey++
                        },
                        onDismiss = { garbageDeleteTarget = null }
                    )
                }

                if (showCreateFolderDialog) {
                    AlertDialog(
                        onDismissRequest = { showCreateFolderDialog = false; newFolderName = "" },
                        title = { Text("Create new folder") },
                        text = {
                                OutlinedTextField(
                                    value = newFolderName,
                                    placeholder = {Text("Folder name")},
                                    onValueChange = { newFolderName = it },
                                    singleLine = true
                                )
                        },
                        confirmButton = {
                            TextButton(
                                shapes = ButtonDefaults.shapes(),
                                onClick = {
                                val name = newFolderName.trim()
                                if (name.isNotEmpty()) {
                                    val newDir = File(musicDir, name)
                                    val created = if (!newDir.exists()) newDir.mkdirs() else true
                                    if (created) {
                                        scope.launch {
                                            showMessage("Folder \"$name\" created")
                                        }
                                        rootDirKey++
                                    } else {
                                        scope.launch {
                                            showMessage("Failed to create folder")
                                        }
                                    }
                                }
                                showCreateFolderDialog = false
                                newFolderName = ""
                                foldersExpanded = false
                            }) { Text("Create", fontWeight = FontWeight.W600, fontSize = 16.sp) }
                        },
                        dismissButton = {
                            TextButton(
                                shapes = ButtonDefaults.shapes(),
                                onClick = { showCreateFolderDialog = false; newFolderName = "" }) {
                                Text("Cancel", fontWeight = FontWeight.W600, fontSize = 16.sp)
                            }
                        }
                    )
                }

                if (showDeleteFolderDialog && !folderToDeleteName.isNullOrEmpty()) {
                    AlertDialog(
                        onDismissRequest = { showDeleteFolderDialog = false; folderToDeleteName = null },
                        title = { Text("Delete folder \"${folderToDeleteName}\"?") },
                        text = {
                            Text("Contents will be moved to Recently deleted, then the folder will be removed.")
                        },
                        confirmButton = {
                            TextButton(
                                shapes = ButtonDefaults.shapes(),
                                onClick = {
                                val name = folderToDeleteName!!
                                val target = File(musicDir, name)
                                try {
                                    (target.listFiles() ?: emptyArray()).filter { it.isFile }.forEach { f ->
                                        try { moveFileToDir(f, garbageDir) } catch (_: Throwable) {}
                                    }
                                    target.deleteRecursively()
                                    scope.launch {
                                        showMessage("Folder \"$name\" deleted")
                                    }

                                } catch (t: Throwable) {
                                    scope.launch {
                                        showMessage("Failed to delete folder")
                                    }
                                }
                                folderToDeleteName = null
                                showDeleteFolderDialog = false
                                rootDirKey++
                                garbageDirKey++
                                currentTab = "home"
                            }) { Text("Delete", fontWeight = FontWeight.W600, fontSize = 16.sp) }
                        },
                        dismissButton = {
                            TextButton(
                                shapes = ButtonDefaults.shapes(),
                                onClick = { showDeleteFolderDialog = false; folderToDeleteName = null }) {
                                Text("Cancel", fontWeight = FontWeight.W600, fontSize = 16.sp)
                            }
                        }
                    )
                }

                DisposableEffect(Unit) {
                    onDispose {
                        try { mediaPlayer.value?.stop() } catch (_: Exception) {}
                        try { mediaPlayer.value?.release() } catch (_: Exception) {}
                        mediaPlayer.value = null
                    }
                }
            }
        }
    }
}


