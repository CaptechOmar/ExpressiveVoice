package com.pranshulgg.recordmaster.screens

import android.Manifest
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.pranshulgg.recordmaster.helpers.RecordQuality
import com.pranshulgg.recordmaster.helpers.StorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.github.skydoves.colorpicker.compose.ColorPickerController
import com.pranshulgg.recordmaster.R
import com.pranshulgg.recordmaster.helpers.PreferencesHelper
import com.pranshulgg.recordmaster.ui.components.ColorPickerSheetTheme
import com.pranshulgg.recordmaster.ui.components.SettingSection
import com.pranshulgg.recordmaster.ui.components.SettingTile
import com.pranshulgg.recordmaster.ui.components.SettingsTileIcon
import com.pranshulgg.recordmaster.ui.components.Symbol
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(
    navController: NavController,
    context: Context,
    onThemeChanged: (Boolean) -> Unit,
    onSeedChanged: (String) -> Unit,
    onDynamicColorChanged: (Boolean) -> Unit,
    onExpressiveColorChanged: (Boolean) -> Unit,
    snackbarHostState: SnackbarHostState
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )
    var showBottomSheet by remember { mutableStateOf(false) }
    val controller = remember { ColorPickerController() }
    val defaultPickerColor = PreferencesHelper.getString("seedColor") ?: "0xff0000FF"
    val initialColorInt = remember(defaultPickerColor) {
        Color(defaultPickerColor.removePrefix("0x").toLong(16).toInt())
    }
    var pickedColor = PreferencesHelper.getString("seedColor") ?: "0xff0000FF"
    var currentTheme by remember {
        mutableStateOf(
            PreferencesHelper.getString("AppTheme") ?: "Dark"
        )
    }

    var useCustomColor by remember {
        mutableStateOf(
            PreferencesHelper.getBool("useCustomColor") ?: false
        )
    }

    var useDynamicColor by remember {
        mutableStateOf(
            PreferencesHelper.getBool("useDynamicColors") ?: false
        )
    }

    var useExpressiveColor by remember {
        mutableStateOf(
            PreferencesHelper.getBool("useExpressiveColor") ?: true
        )
    }

    fun hideColorSheet() {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) {
                showBottomSheet = false
            }
        }
    }

    @Composable
    fun openColorPickerLead() {
        Surface(
            shape = RoundedCornerShape(50.dp),
            border = BorderStroke(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        ) {
            Box(

                modifier = Modifier
                    .width(24.dp)
                    .height(36.dp)
                    .background(
                        color = Color(
                            defaultPickerColor.removePrefix("0x").toLong(16).toInt()
                        )
                    )
                    .clickable(
//                        onClick = { showBottomSheet = true }
                        onClick = {
                            showBottomSheet = true

                        }
                    ),
            ) {
            }
        }
    }




    // -------------------- Recordings folder --------------------

    var recordQuality by remember { mutableStateOf(RecordQuality.current()) }
    var customPath by remember { mutableStateOf(StorageHelper.customRecordingsPath()) }
    var accessTick by remember { mutableStateOf(0) }
    val effectiveDir = remember(customPath, accessTick) { StorageHelper.getRecordingsDir(context) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var pendingPickAfterPermission by remember { mutableStateOf(false) }
    var pendingChange by remember { mutableStateOf<FolderChange?>(null) }

    fun message(text: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(text)
        }
    }

    fun applyFolder(newPath: String?, moveExisting: Boolean) {
        val oldDir = StorageHelper.getRecordingsDir(context)
        StorageHelper.setCustomRecordingsPath(newPath)
        customPath = newPath
        val newDir = StorageHelper.getRecordingsDir(context)
        if (moveExisting && oldDir.canonicalPath != newDir.canonicalPath) {
            scope.launch {
                val failed = withContext(Dispatchers.IO) { StorageHelper.moveAllRecordings(oldDir, newDir) }
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(
                    if (failed == 0) "Recordings moved" else "$failed item(s) couldn't be moved"
                )
            }
        } else {
            message("Recordings folder updated")
        }
    }

    /** Asks whether to move existing recordings when there are any, otherwise just switches. */
    fun requestFolderChange(newPath: String?) {
        val oldDir = StorageHelper.getRecordingsDir(context)
        val newDir = newPath?.let { File(it) } ?: StorageHelper.defaultRecordingsDir(context)
        val sameFolder = oldDir.canonicalPath == newDir.canonicalPath
        val hasRecordings = !oldDir.listFiles().isNullOrEmpty()
        val nested = StorageHelper.isSameOrInside(newDir, oldDir) ||
                StorageHelper.isSameOrInside(oldDir, newDir)
        if (sameFolder) {
            applyFolder(newPath, moveExisting = false)
        } else if (hasRecordings && !nested) {
            pendingChange = FolderChange(newPath)
        } else {
            applyFolder(newPath, moveExisting = false)
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val dir = StorageHelper.treeUriToPath(uri)?.let { File(it) }
        when {
            dir == null ->
                message("That location isn't supported. Pick a folder on internal storage or an SD card")
            !(dir.isDirectory || dir.mkdirs()) || !dir.canWrite() ->
                message("Can't write to that folder")
            else -> requestFolderChange(dir.absolutePath)
        }
    }

    val legacyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        accessTick++
        if (granted) folderPicker.launch(null) else message("Storage permission is required to choose a folder")
    }

    fun startPickFolder() {
        if (StorageHelper.hasStorageAccess(context)) folderPicker.launch(null)
        else showPermissionDialog = true
    }

    // Coming back from the "All files access" screen: continue with the picker if it was granted.
    val lifecycleOwner = context as? LifecycleOwner
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessTick++
                if (pendingPickAfterPermission) {
                    pendingPickAfterPermission = false
                    if (StorageHelper.hasStorageAccess(context)) folderPicker.launch(null)
                }
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text("Allow access to files") },
            text = {
                Text(
                    "To save recordings in a folder of your choice, RecordMaster needs permission to " +
                            "manage files on your device."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionDialog = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        pendingPickAfterPermission = true
                        try {
                            context.startActivity(StorageHelper.allFilesAccessIntent(context))
                        } catch (_: Exception) {
                            try {
                                context.startActivity(
                                    android.content.Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                )
                            } catch (_: Exception) {
                                pendingPickAfterPermission = false
                                message("Couldn't open the permission screen")
                            }
                        }
                    } else {
                        legacyPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) { Text("Cancel") }
            }
        )
    }

    pendingChange?.let { change ->
        AlertDialog(
            onDismissRequest = { pendingChange = null },
            title = { Text("Move existing recordings?") },
            text = {
                Text(
                    "Move your current recordings and folders to the new location, or leave them " +
                            "where they are. Recordings left behind won't show up in the app."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingChange = null
                    applyFolder(change.newPath, moveExisting = true)
                }) { Text("Move") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingChange = null
                    applyFolder(change.newPath, moveExisting = false)
                }) { Text("Don't move") }
            }
        )
    }

    val isSysDark = isSystemInDarkTheme()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LargeTopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(
                        onClick = { navController.popBackStack() }
                    ) {
                        Symbol(
                            R.drawable.arrow_back_24px,
                            desc = "Back",
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        }
    ) { innerPadding ->

        LazyColumn(
            modifier = Modifier
                .padding(top = innerPadding.calculateTopPadding())
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
        ) {
            item {
                SettingSection(
                    title = "App looks",
                    tiles = listOf(
                        SettingTile.DialogOptionTile(
                            leading = { SettingsTileIcon(R.drawable.routine_24px) },
                            title = "App Theme",
                            options = listOf("Light", "Dark", "System"),
                            selectedOption = currentTheme,
                            onOptionSelected = { selectedOption ->
                                currentTheme = selectedOption
                                val isDark = when (selectedOption) {
                                    "Light" -> false
                                    "Dark" -> true
                                    "System" -> isSysDark
                                    else -> false
                                }
                                PreferencesHelper.setBool("dark_theme", isDark)
                                PreferencesHelper.setString("AppTheme", selectedOption)

                                onThemeChanged(isDark)

                            }
                        ),
                        SettingTile.SwitchTile(
                            leading = {
                                if (useCustomColor) openColorPickerLead() else SettingsTileIcon(
                                    R.drawable.colorize_24px
                                )
                            },
                            title = "Use custom color",
                            description = "Select a seed color to generate the theme",
                            checked = useCustomColor,
                            enabled = !useDynamicColor,
                            onCheckedChange = { checked ->
                                PreferencesHelper.setBool("useCustomColor", checked)

                                useCustomColor = checked

                                if (!checked) {
                                    PreferencesHelper.setString("seedColor", "0xff0000FF")
                                    onSeedChanged("0xff0000FF")
                                }

                            }
                        ),
                        SettingTile.SwitchTile(
                            leading = {
                                SettingsTileIcon(
                                    R.drawable.image_24px
                                )
                            },
                            title = "Dynamic colors",
                            description = "Use wallpaper colors",
                            checked = useDynamicColor,
                            enabled = !useCustomColor,
                            onCheckedChange = { checked ->
                                PreferencesHelper.setBool("useDynamicColors", checked)
                                PreferencesHelper.setBool("useCustomColor", !checked)
                                onDynamicColorChanged(checked)
                                if (useCustomColor) {
                                    useCustomColor = !checked
                                }
                                useDynamicColor = checked
                            }
                        ),
                    )
                )
            }
            item {
                Spacer(Modifier.height(16.dp))
                SettingSection(
                    title = "Recording",
                    tiles = listOfNotNull(
                        SettingTile.DialogOptionTile(
                            leading = { SettingsTileIcon(R.drawable.graphic_eq_24px) },
                            title = "Recording quality",
                            dialogTitle = "Recording quality",
                            options = RecordQuality.entries.map { it.key },
                            selectedOption = recordQuality.key,
                            optionLabel = { RecordQuality.fromKey(it).label },
                            onOptionSelected = { key ->
                                PreferencesHelper.setString(RecordQuality.PREF_KEY, key)
                                recordQuality = RecordQuality.fromKey(key)
                            }
                        ),
                        SettingTile.ActionTile(
                            leading = { SettingsTileIcon(R.drawable.folder_24px) },
                            title = "Recordings folder",
                            description = effectiveDir.absolutePath +
                                    if (customPath != null && customPath != effectiveDir.absolutePath)
                                        "\nSelected folder unavailable, using default" else "",
                            onClick = { startPickFolder() }
                        ),
                        if (customPath != null) SettingTile.ActionTile(
                            leading = { SettingsTileIcon(R.drawable.refresh_24px) },
                            title = "Use default folder",
                            description = "Save recordings in the app's own storage",
                            onClick = { requestFolderChange(null) }
                        ) else null
                    )
                )
            }
            item {
                Spacer(Modifier.height(16.dp))
                SettingSection(
                    title = "Additional",
                    tiles = listOf(
                        SettingTile.ActionTile(
                            leading = {SettingsTileIcon(R.drawable.info_24px)},
                            title = "About app",
                            description = "Terms, Version, License, and More",
                            onClick = {
                                navController.navigate("OpenAboutScreen")
                            },
                        )
                    )
                )
            }
        }

        if(showBottomSheet){
            ColorPickerSheetTheme (
                onShowSheet = showBottomSheet,
                sheetState = sheetState,
                initialColorInt = initialColorInt,
                controller = controller,
                onPickedColor = { argbHex ->
                    pickedColor = argbHex
                },
                onExpressiveColorChanged = { checked ->
                    PreferencesHelper.setBool("useExpressiveColor", checked)

                    useExpressiveColor = checked
                    onExpressiveColorChanged(checked)
                },
                useExpressiveColor = useExpressiveColor,
                onSeedChanged = {
                    onSeedChanged(pickedColor)
                    PreferencesHelper.setString("seedColor", pickedColor)
                },
                hideColorSheet ={
                    hideColorSheet()
                }
            )
        }

    }
}

private data class FolderChange(val newPath: String?)
