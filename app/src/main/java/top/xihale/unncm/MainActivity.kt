package top.xihale.unncm

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.xihale.unncm.databinding.ActivityMainBinding
import top.xihale.unncm.utils.Logger
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

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
    }

    private val logger = Logger.withTag("MainActivity")

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: FileAdapter

    private val inputFolderRequestCode = 101
    private var currentFolderRequestCode: Int = 0
    private var sourceMode: SourceMode = SourceMode.NONE
    private var isRestoringState: Boolean = false
    private var folderScanInFlight: Boolean = false
    private var persistPendingFilesJob: Job? = null
    private val ncmPickerMimeTypes = arrayOf("application/octet-stream")

    private val openDocumentTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            logger.d("Folder picker result received: $uri")
            if (uri == null || currentFolderRequestCode != inputFolderRequestCode) {
                return@registerForActivityResult
            }

            binding.tvStatus.text = getString(R.string.status_scanning)

            lifecycleScope.launch {
                try {
                    val (permissionGranted, inputDir) = withContext(Dispatchers.IO) {
                        val granted = tryPersistTreeReadWritePermission(uri)
                        val directory = if (granted) DocumentFile.fromTreeUri(this@MainActivity, uri) else null
                        granted to directory
                    }

                    if (!permissionGranted) {
                        binding.tvStatus.text = getString(R.string.msg_permission_denied)
                        return@launch
                    }

                    if (inputDir == null) {
                        binding.tvStatus.text = getString(R.string.msg_invalid_directory)
                        return@launch
                    }

                    sourceMode = SourceMode.FOLDER
                    persistSourceMode(sourceMode)
                    persistFolderSelection(uri)
                    folderScanInFlight = true
                    viewModel.setOutputDir(null)
                    viewModel.setInputDir(inputDir)
                    viewModel.scanFiles()
                } catch (e: Exception) {
                    logger.e("Error handling folder selection", e)
                    binding.tvStatus.text = e.message ?: getString(R.string.status_failed)
                }
            }
        }

    private val openMultipleFilesLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
            if (uris.isEmpty()) {
                if (viewModel.conversionStatus.value is ConversionUiState.Idle) {
                    renderIdleStatus(viewModel.pendingFiles.value.size)
                }
                return@registerForActivityResult
            }

            lifecycleScope.launch {
                val (selectedFiles, skippedPermissionCount) = withContext(Dispatchers.IO) {
                    buildSelectedFilesFromUris(uris)
                }

                if (skippedPermissionCount > 0) {
                    binding.tvStatus.text = getString(R.string.msg_files_skipped_not_persisted, skippedPermissionCount)
                }

                if (selectedFiles.isEmpty()) {
                    binding.tvStatus.text = getString(R.string.msg_no_ncm_files_selected)
                    return@launch
                }

                val outputDir = ensureDirectPickOutputDirectory() ?: run {
                    binding.tvStatus.text = getString(R.string.msg_create_output_fail)
                    return@launch
                }

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

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        DynamicColors.applyToActivityIfAvailable(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applySystemInsets()

        adapter = FileAdapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        setupSwipeToDelete()

        isRestoringState = true
        setupButtons()
        setupObservers()

        val restored = restoreSessionState()
        isRestoringState = false
        if (restored) {
            if (sourceMode == SourceMode.FOLDER && viewModel.pendingFiles.value.isEmpty()) {
                folderScanInFlight = true
                viewModel.scanFiles()
            } else if (sourceMode == SourceMode.FOLDER) {
                clearPersistedPendingFileSelection()
            }
        }

        renderIdleStatus(viewModel.pendingFiles.value.size)
        updateEmptyState(viewModel.pendingFiles.value.size, viewModel.conversionStatus.value)
        binding.tvThreadLabel.text = getString(R.string.label_threads, binding.sliderThreads.value.toInt())
    }

    private fun applySystemInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val topBase = resources.getDimensionPixelSize(R.dimen.space_md)
            val bottomBase = resources.getDimensionPixelSize(R.dimen.space_md)
            view.updatePadding(
                top = topBase + systemBars.top,
                bottom = bottomBase + systemBars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun setupButtons() {
        binding.btnPickFiles.setOnClickListener {
            openMultipleFilesLauncher.launch(ncmPickerMimeTypes)
            overridePendingTransition(0, 0)
        }

        binding.btnPickFolder.setOnClickListener {
            openFolderPicker(inputFolderRequestCode)
        }

        binding.sliderThreads.addOnChangeListener(
            object : Slider.OnChangeListener {
                override fun onValueChange(slider: Slider, value: Float, fromUser: Boolean) {
                    if (fromUser) {
                        binding.tvThreadLabel.text = getString(R.string.label_threads, value.toInt())
                    }
                }
            }
        )

        binding.btnConvert.setOnClickListener {
            val pendingFilesList = viewModel.pendingFiles.value
            if (pendingFilesList.isEmpty()) {
                binding.tvStatus.text = getString(R.string.no_files_found)
                return@setOnClickListener
            }

            val maxThreads = binding.sliderThreads.value.toInt()
            viewModel.convertFiles(maxThreads, cacheDir)
        }
    }

    private fun setupSwipeToDelete() {
        val swipeBackground = ColorDrawable(
            MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorError)
        )

        val swipeCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun getAnimationDuration(
                recyclerView: RecyclerView,
                animationType: Int,
                animateDx: Float,
                animateDy: Float
            ): Long {
                return if (animationType == ItemTouchHelper.ANIMATION_TYPE_SWIPE_SUCCESS) 120L else 100L
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                return false
            }

            override fun getSwipeDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                val state = viewModel.conversionStatus.value
                val isBusy = state is ConversionUiState.Scanning || state is ConversionUiState.Converting
                return if (isBusy) 0 else super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                if (position == RecyclerView.NO_POSITION) {
                    return
                }

                val swipedFile = adapter.currentList.getOrNull(position)
                if (swipedFile == null) {
                    adapter.notifyItemChanged(position)
                    return
                }

                viewModel.removePendingFiles(listOf(swipedFile))
                binding.tvStatus.text = getString(R.string.msg_file_removed, swipedFile.fileName)
            }

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                    val itemView = viewHolder.itemView
                    when {
                        dX > 0f -> {
                            swipeBackground.setBounds(
                                itemView.left,
                                itemView.top,
                                itemView.left + dX.toInt(),
                                itemView.bottom
                            )
                        }

                        dX < 0f -> {
                            swipeBackground.setBounds(
                                itemView.right + dX.toInt(),
                                itemView.top,
                                itemView.right,
                                itemView.bottom
                            )
                        }

                        else -> {
                            swipeBackground.setBounds(0, 0, 0, 0)
                        }
                    }
                    swipeBackground.draw(c)
                }

                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        }

        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.recyclerView)
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            viewModel.pendingFiles.collect { files ->
                adapter.submitList(files.map { it.copy() })
                if (!isRestoringState && sourceMode == SourceMode.FILES) {
                    persistPendingFileSelectionAsync(files)
                }
                if (viewModel.conversionStatus.value is ConversionUiState.Idle) {
                    renderIdleStatus(files.size)
                }
                updateEmptyState(files.size, viewModel.conversionStatus.value)
            }
        }

        viewModel.conversionStatus.observe(this) { state ->
            when (state) {
                is ConversionUiState.Idle -> {
                    binding.btnConvert.isEnabled = true
                    binding.btnConvert.text = getString(R.string.convert_button)
                    if (sourceMode == SourceMode.FOLDER && folderScanInFlight) {
                        clearPersistedPendingFileSelection()
                        folderScanInFlight = false
                    }
                    renderIdleStatus(viewModel.pendingFiles.value.size)
                }

                is ConversionUiState.Scanning -> {
                    binding.btnConvert.isEnabled = false
                    binding.btnConvert.text = getString(R.string.status_scanning)
                    binding.tvStatus.text = getString(R.string.status_scanning)
                }

                is ConversionUiState.Converting -> {
                    binding.btnConvert.isEnabled = false
                    binding.btnConvert.text = getString(R.string.status_converting)
                    binding.tvStatus.text = getString(R.string.status_converting)
                }

                is ConversionUiState.Completed -> {
                    binding.btnConvert.isEnabled = true
                    binding.btnConvert.text = getString(R.string.convert_button)
                    binding.tvStatus.text = getString(R.string.msg_convert_complete)
                    viewModel.resetConversionStatus()
                }

                is ConversionUiState.Error -> {
                    binding.btnConvert.isEnabled = true
                    binding.btnConvert.text = getString(R.string.convert_button)
                    var errorMessage = state.message
                    if (folderScanInFlight && sourceMode == SourceMode.FOLDER) {
                        folderScanInFlight = false
                        val restored = restorePendingFileSelection(settingsPrefs())
                        if (restored) {
                            errorMessage = getString(R.string.msg_folder_scan_failed_restore_files)
                        }
                    }
                    binding.tvStatus.text = errorMessage
                    viewModel.resetConversionStatus()
                }
            }

            updateEmptyState(viewModel.pendingFiles.value.size, state)
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

    private fun renderIdleStatus(totalCount: Int) {
        binding.tvStatus.text = when (sourceMode) {
            SourceMode.NONE -> getString(R.string.status_idle_summary)
            SourceMode.FILES -> getString(R.string.status_files_selected, totalCount)
            SourceMode.FOLDER -> getString(R.string.status_folder_selected, totalCount)
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

    private fun updateEmptyState(totalCount: Int, state: ConversionUiState?) {
        val isBusy = state is ConversionUiState.Scanning || state is ConversionUiState.Converting
        val showEmpty = totalCount == 0 && !isBusy
        binding.emptyStateContainer.visibility = if (showEmpty) android.view.View.VISIBLE else android.view.View.GONE
        binding.recyclerView.visibility = if (showEmpty) android.view.View.INVISIBLE else android.view.View.VISIBLE
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
        folderScanInFlight = false
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

    private fun openFolderPicker(requestCode: Int) {
        currentFolderRequestCode = requestCode
        // Fast launch: 不传 initialUri，避免在点击瞬间触发额外 URI 校验和 provider 定位开销。
        openDocumentTreeLauncher.launch(null)
        overridePendingTransition(0, 0)
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
