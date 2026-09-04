package top.xihale.unncm

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.xihale.unncm.ui.screens.MainScreen
import top.xihale.unncm.ui.theme.UnNcmTheme
import top.xihale.unncm.utils.Logger
import java.io.File

class MainActivity : ComponentActivity() {

    private enum class SourceMode {
        NONE,
        FILES,
        FOLDER;

        companion object {
            fun fromStored(value: String?): SourceMode {
                return values().firstOrNull { it.name == value } ?: NONE
            }
        }
    }

    companion object {
        private const val PREF_SETTINGS = "settings"
        private const val KEY_INPUT_URI = "input_uri"
        private const val KEY_INPUT_PATH = "input_path"
        private const val KEY_SOURCE_MODE = "source_mode"
        private const val KEY_PENDING_FILES_JSON = "pending_files_json"
        private const val KEY_THREADS = "threads"
        private const val DEFAULT_THREADS = 4
    }

    private val logger = Logger.withTag("MainActivity")
    private val viewModel: MainViewModel by viewModels()

    private var sourceMode: SourceMode = SourceMode.NONE
    private var isRestoringState: Boolean = false
    private var persistPendingFilesJob: Job? = null
    private val ncmPickerMimeTypes = arrayOf("application/octet-stream")

    private val openDocumentTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            logger.d("Folder picker result received: $uri")
            if (uri == null) return@registerForActivityResult

            lifecycleScope.launch {
                try {
                    val (permissionGranted, inputDir) = withContext(Dispatchers.IO) {
                        val granted = tryPersistTreeReadWritePermission(uri)
                        val directory = if (granted) DocumentFile.fromTreeUri(this@MainActivity, uri) else null
                        granted to directory
                    }

                    if (!permissionGranted || inputDir == null) {
                        return@launch
                    }

                    sourceMode = SourceMode.FOLDER
                    persistSourceMode(sourceMode)
                    persistFolderSelection(uri)
                    viewModel.setOutputDir(null)
                    viewModel.setInputDir(inputDir)
                    viewModel.scanFiles()
                } catch (e: Exception) {
                    logger.e("Error handling folder selection", e)
                }
            }
        }

    private val openMultipleFilesLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
            if (uris.isEmpty()) return@registerForActivityResult

            lifecycleScope.launch {
                val (selectedFiles, _) = withContext(Dispatchers.IO) {
                    buildSelectedFilesFromUris(uris)
                }

                if (selectedFiles.isEmpty()) return@launch

                val outputDir = ensureDirectPickOutputDirectory() ?: return@launch

                sourceMode = SourceMode.FILES
                persistSourceMode(sourceMode)
                viewModel.setInputDir(null)
                viewModel.setOutputDir(outputDir)
                viewModel.setPendingFiles(selectedFiles)
                viewModel.resetConversionStatus()
                persistPendingFileSelectionAsync(selectedFiles)
            }
        }

    private fun buildSelectedFilesFromUris(uris: List<Uri>): Pair<List<UiFile>, Int> {
        var skippedPermissionCount = 0
        val selectedFiles = uris.mapNotNull { uri ->
            val fileName = DocumentFile.fromSingleUri(this, uri)?.name
                ?: uri.lastPathSegment?.substringAfterLast('/')
                ?: return@mapNotNull null

            if (!fileName.endsWith(".ncm", ignoreCase = true)) {
                return@mapNotNull null
            }

            if (!tryPersistDocumentPermission(uri)) {
                skippedPermissionCount += 1
                return@mapNotNull null
            }

            UiFile(uri = uri, fileName = fileName)
        }

        return selectedFiles to skippedPermissionCount
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        isRestoringState = true
        val restored = restoreSessionState()
        isRestoringState = false
        if (restored) {
            if (sourceMode == SourceMode.FOLDER && viewModel.pendingFiles.value.isEmpty()) {
                viewModel.scanFiles()
            } else if (sourceMode == SourceMode.FOLDER) {
                clearPersistedPendingFileSelection()
            }
        }

        val prefs = settingsPrefs()
        var initialThreads = prefs.getInt(KEY_THREADS, DEFAULT_THREADS).coerceIn(1, 8)

        setContent {
            UnNcmTheme {
                val pendingFiles by viewModel.pendingFiles.collectAsStateWithLifecycle()
                val conversionStatus by viewModel.conversionStatus.collectAsStateWithLifecycle()
                val inputDir by viewModel.inputDir.collectAsStateWithLifecycle()
                val outputDir by viewModel.outputDir.collectAsStateWithLifecycle()

                var threads by remember { mutableIntStateOf(initialThreads) }
                val snackbarHostState = remember { SnackbarHostState() }

                // 错误提示反馈
                LaunchedEffect(conversionStatus) {
                    if (conversionStatus is ConversionUiState.Error) {
                        snackbarHostState.showSnackbar((conversionStatus as ConversionUiState.Error).message)
                    }
                }

                val folderName = when (sourceMode) {
                    SourceMode.FOLDER -> inputDir?.name ?: "已选文件夹"
                    SourceMode.FILES -> if (pendingFiles.isNotEmpty()) "直接选取 ${pendingFiles.size} 个文件" else null
                    SourceMode.NONE -> null
                }

                val outputPath = outputDir?.name ?: inputDir?.let { "${it.name}/unlocked" } ?: "unlocked"

                MainScreen(
                    pendingFiles = pendingFiles,
                    conversionStatus = conversionStatus,
                    folderName = folderName,
                    outputPath = outputPath,
                    threads = threads,
                    onThreadsChange = { count ->
                        threads = count
                        settingsPrefs().edit { putInt(KEY_THREADS, count) }
                    },
                    onPickFiles = {
                        openMultipleFilesLauncher.launch(ncmPickerMimeTypes)
                    },
                    onPickFolder = {
                        openDocumentTreeLauncher.launch(null)
                    },
                    onRemoveFile = { file ->
                        viewModel.removePendingFile(file)
                        if (sourceMode == SourceMode.FILES) {
                            persistPendingFileSelectionAsync(viewModel.pendingFiles.value)
                        }
                    },
                    onClearAll = {
                        viewModel.clearPendingFiles()
                        if (sourceMode == SourceMode.FILES) {
                            clearPersistedPendingFileSelection()
                        }
                    },
                    onStartConversion = {
                        viewModel.convertFiles(threads, cacheDir)
                    },
                    onStopConversion = {
                        viewModel.cancelConversion()
                    },
                    snackbarHostState = snackbarHostState
                )
            }
        }
    }

    override fun onDestroy() {
        persistPendingFilesJob?.cancel()
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        if (!isRestoringState && sourceMode == SourceMode.FILES) {
            persistPendingFileSelectionAsync(viewModel.pendingFiles.value)
        }
    }

    private fun ensureDirectPickOutputDirectory(): DocumentFile? {
        val baseDir = getExternalFilesDir(null) ?: filesDir
        val unlockedDir = File(baseDir, "unlocked")
        if (!unlockedDir.exists() && !unlockedDir.mkdirs()) {
            logger.e("Failed to create direct-pick output dir: ${unlockedDir.absolutePath}")
            return null
        }
        return DocumentFile.fromFile(unlockedDir)
    }

    private fun restoreSessionState(): Boolean {
        val prefs = settingsPrefs()
        val storedMode = prefs.getString(KEY_SOURCE_MODE, null)

        val restored = if (storedMode == null) {
            restorePendingFileSelection(prefs) || restoreFolderSelection(prefs)
        } else {
            when (SourceMode.fromStored(storedMode)) {
                SourceMode.FILES -> restorePendingFileSelection(prefs)
                SourceMode.FOLDER -> restoreFolderSelection(prefs) || restorePendingFileSelection(prefs)
                SourceMode.NONE -> false
            }
        }

        if (!restored) {
            sourceMode = SourceMode.NONE
            persistSourceMode(sourceMode)
        }
        return restored
    }

    private fun restorePendingFileSelection(prefs: SharedPreferences): Boolean {
        val json = prefs.getString(KEY_PENDING_FILES_JSON, null)
        if (json.isNullOrBlank()) {
            return false
        }

        val restoredFiles = parsePendingFiles(json)
            .filter { uiFile -> canAccessPersistedUri(uiFile.uri) }

        if (restoredFiles.isEmpty()) {
            clearPersistedPendingFileSelection()
            return false
        }

        val outputDir = ensureDirectPickOutputDirectory() ?: run {
            logger.e("Failed to restore pending file selection: no output directory")
            return false
        }

        sourceMode = SourceMode.FILES
        persistSourceMode(sourceMode)
        viewModel.setInputDir(null)
        viewModel.setOutputDir(outputDir)
        viewModel.setPendingFiles(restoredFiles)
        viewModel.resetConversionStatus()
        persistPendingFileSelectionAsync(restoredFiles)
        return true
    }

    private fun restoreFolderSelection(prefs: SharedPreferences): Boolean {
        val savedInputUri = prefs.getString(KEY_INPUT_URI, null)
        val savedInputPath = prefs.getString(KEY_INPUT_PATH, null)

        if (savedInputUri != null) {
            val uri = Uri.parse(savedInputUri)
            try {
                if (!hasPersistedTreeReadWritePermission(uri)) {
                    val persisted = tryPersistTreeReadWritePermission(uri)
                    if (!persisted || !hasPersistedTreeReadWritePermission(uri)) {
                        logger.e("Failed to restore read/write tree permission for $uri")
                        prefs.edit().remove(KEY_INPUT_URI).apply()
                        return false
                    }
                }

                DocumentFile.fromTreeUri(this, uri)?.takeIf { it.exists() }?.let { docFile ->
                    sourceMode = SourceMode.FOLDER
                    persistSourceMode(sourceMode)
                    viewModel.setInputDir(docFile)
                    return true
                }

                prefs.edit().remove(KEY_INPUT_URI).apply()
            } catch (e: Exception) {
                logger.w("Failed to restore saved input URI", e)
            }
        }

        if (savedInputPath != null) {
            File(savedInputPath).takeIf { it.exists() && it.isDirectory }?.let { file ->
                val docFile = DocumentFile.fromFile(file)
                sourceMode = SourceMode.FOLDER
                persistSourceMode(sourceMode)
                viewModel.setInputDir(docFile)
                return true
            }
            prefs.edit().remove(KEY_INPUT_PATH).apply()
        }

        return false
    }

    private fun settingsPrefs(): SharedPreferences {
        return getSharedPreferences(PREF_SETTINGS, MODE_PRIVATE)
    }

    private fun persistSourceMode(mode: SourceMode) {
        settingsPrefs().edit {
            putString(KEY_SOURCE_MODE, mode.name)
        }
    }

    private fun persistFolderSelection(uri: Uri) {
        settingsPrefs().edit {
            putString(KEY_INPUT_URI, uri.toString())
            putString(KEY_SOURCE_MODE, SourceMode.FOLDER.name)
        }
    }

    private fun persistPendingFileSelectionAsync(files: List<UiFile>) {
        val filesSnapshot = files.toList()
        val modeSnapshot = sourceMode
        persistPendingFilesJob?.cancel()
        persistPendingFilesJob = lifecycleScope.launch(Dispatchers.IO) {
            persistPendingFileSelection(filesSnapshot, modeSnapshot)
        }
    }

    private fun persistPendingFileSelection(files: List<UiFile>, mode: SourceMode = sourceMode) {
        val pendingFiles = files.filter { it.status != FileStatus.DONE }
        if (pendingFiles.isEmpty()) {
            settingsPrefs().edit {
                remove(KEY_PENDING_FILES_JSON)
                if (mode == SourceMode.FILES) {
                    putString(KEY_SOURCE_MODE, SourceMode.NONE.name)
                }
            }
            return
        }

        val jsonArray = JSONArray()
        pendingFiles.forEach { file ->
            val item = JSONObject().apply {
                put("uri", file.uri.toString())
                put("fileName", file.fileName)
                put("status", file.status.name)
            }
            jsonArray.put(item)
        }

        settingsPrefs().edit {
            putString(KEY_PENDING_FILES_JSON, jsonArray.toString())
            putString(KEY_SOURCE_MODE, SourceMode.FILES.name)
        }
    }

    private fun clearPersistedPendingFileSelection() {
        settingsPrefs().edit {
            remove(KEY_PENDING_FILES_JSON)
        }
    }

    private fun parsePendingFiles(serialized: String): List<UiFile> {
        return try {
            val array = JSONArray(serialized)
            val files = mutableListOf<UiFile>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val uriRaw = item.optString("uri")
                val fileName = item.optString("fileName")
                if (uriRaw.isBlank() || fileName.isBlank()) {
                    continue
                }

                val statusName = item.optString("status", FileStatus.PENDING.name)
                val status = FileStatus.values().firstOrNull { it.name == statusName } ?: FileStatus.PENDING
                if (status == FileStatus.DONE) {
                    continue
                }

                files.add(
                    UiFile(
                        uri = Uri.parse(uriRaw),
                        fileName = fileName,
                        status = FileStatus.PENDING
                    )
                )
            }
            files
        } catch (e: Exception) {
            logger.w("Failed to parse pending files state", e)
            emptyList()
        }
    }

    private fun canAccessPersistedUri(uri: Uri): Boolean {
        if (!hasPersistedReadPermission(uri)) {
            return false
        }
        return try {
            val documentFile = DocumentFile.fromSingleUri(this, uri) ?: return false
            documentFile.exists()
        } catch (e: SecurityException) {
            logger.w("Lost permission for persisted URI: $uri", e)
            false
        } catch (e: Exception) {
            logger.w("Failed checking persisted URI access: $uri", e)
            false
        }
    }

    private fun tryPersistDocumentPermission(uri: Uri): Boolean {
        val readAndWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        return try {
            contentResolver.takePersistableUriPermission(uri, readAndWrite)
            true
        } catch (e: SecurityException) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                true
            } catch (retryError: SecurityException) {
                logger.w("Persist URI read permission failed for $uri", retryError)
                false
            }
        }
    }

    private fun tryPersistTreeReadWritePermission(uri: Uri): Boolean {
        val readAndWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        return try {
            contentResolver.takePersistableUriPermission(uri, readAndWrite)
            true
        } catch (e: SecurityException) {
            logger.w("Persist tree read/write permission failed for $uri", e)
            false
        }
    }

    private fun hasPersistedReadPermission(uri: Uri): Boolean {
        return contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isReadPermission
        }
    }

    private fun hasPersistedTreeReadWritePermission(uri: Uri): Boolean {
        return contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isReadPermission && permission.isWritePermission
        }
    }
}
