package top.xihale.unncm

import android.app.Application
import android.os.Looper
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import top.xihale.unncm.utils.FastScanner
import top.xihale.unncm.utils.Logger
import top.xihale.unncm.MediaMetadataRetrieverHelper
import java.io.File
sealed class ConversionUiState {
    object Idle : ConversionUiState()
    object Scanning : ConversionUiState()
    object Converting : ConversionUiState()
    object Completed : ConversionUiState()
    data class Error(val message: String) : ConversionUiState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val logger = Logger.withTag("MainViewModel")

    // LiveData for UI state
    private val _inputDir = MutableLiveData<DocumentFile?>()
    val inputDir: LiveData<DocumentFile?> = _inputDir

    private val _outputDir = MutableLiveData<DocumentFile?>()
    val outputDir: LiveData<DocumentFile?> = _outputDir

    private val _pendingFiles = MutableStateFlow<List<UiFile>>(emptyList())
    val pendingFiles: StateFlow<List<UiFile>> = _pendingFiles.asStateFlow()

    private val _conversionStatus = MutableLiveData<ConversionUiState>()
    val conversionStatus: LiveData<ConversionUiState> = _conversionStatus

    private var scanJob: Job? = null
    private var conversionJob: Job? = null

    init {
        val id = System.identityHashCode(this)
        logger.d("MainViewModel initialized. Instance ID: $id")

        // Initialize with empty list
        _pendingFiles.value = emptyList()
        _conversionStatus.value = ConversionUiState.Idle
    }

    fun setInputDir(documentFile: DocumentFile?) {
        _inputDir.value = documentFile.also { logger.d("Input document file set: ${it?.name}") }
    }

    fun setOutputDir(documentFile: DocumentFile?) {
        val target = documentFile.also { logger.d("Output document file set: ${it?.name}") }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            _outputDir.value = target
        } else {
            _outputDir.postValue(target)
        }
    }

    fun setPendingFiles(files: List<UiFile>) {
        _pendingFiles.value = files.also {
            logger.d("Pending files updated: ${it.size} files")
        }
    }

    fun removePendingFile(file: UiFile) {
        _pendingFiles.update { currentList ->
            (currentList - file).also {
                logger.d("Removed pending file: ${file.fileName}")
            }
        }
    }

    fun removePendingFiles(files: List<UiFile>) {
        if (files.isEmpty()) return
        val targetUris = files.map { it.uri }.toSet()
        _pendingFiles.update { currentList ->
            currentList.filterNot { targetUris.contains(it.uri) }
        }
        logger.d("Removed ${files.size} pending files by selection")
    }

    fun clearPendingFiles() {
        _pendingFiles.value = emptyList<UiFile>().also {
            logger.d("Pending files cleared")
        }
    }

    fun resetConversionStatus() {
        _conversionStatus.value = ConversionUiState.Idle
    }


    private fun isValidOutputDirectory(docFile: DocumentFile): Boolean {
        return !(!docFile.exists() || !docFile.isDirectory)
    }

    fun scanFiles() {
        logger.d("scanFiles called")
        if (_conversionStatus.value is ConversionUiState.Scanning) return

        val inputDir = _inputDir.value
        logger.d("Input dir check: file=${inputDir?.name}, uri=${inputDir?.uri}, exists=${inputDir?.exists()}")

        // Early validation returns
        if (inputDir == null || !inputDir.exists()) {
            logger.e("Input directory invalid or null")
            _conversionStatus.value =
                ConversionUiState.Error(getApplication<Application>().getString(R.string.msg_invalid_input_dir))
            return
        }

        scanJob?.cancel()
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            _conversionStatus.postValue(ConversionUiState.Scanning)
            clearPendingFiles() // Clear existing list

            try {
                val unlockedDir = setupOutputDirectory(inputDir) ?: return@launch

                logger.i("=== Starting file scan ===")
                val outputBaseNames = getOutputBaseNames(unlockedDir)
                
                val batch = mutableListOf<UiFile>()
                
                FastScanner.scanAudioFilesFlow(getApplication(), inputDir.uri)
                    .buffer(100)
                                        .collect { uiFile ->
                                            val isNcm = uiFile.fileName.endsWith(".ncm", ignoreCase = true)
                                            val baseName = uiFile.fileName.substringBeforeLast('.')
                                            
                                            if (isNcm) {
                                                // Skip NCM files that are already in output directory
                                                if (baseName in outputBaseNames) {
                                                    return@collect
                                                }
                                            } else {
                                                // Regular audio file: Check if metadata is missing
                                                // This is a blocking call, but we are on IO thread.
                                                // For very large folders, this might slow down the scan, but it's necessary requirement.
                                                val needsProcessing = MediaMetadataRetrieverHelper.analyzeMetadataFromUri(getApplication(), uiFile.uri)
                                                if (!needsProcessing) {
                                                    return@collect
                                                }
                                            }
                                            
                                            batch.add(uiFile)                        
                        // Update UI in batches
                        if (batch.size >= 50) {
                            val batchCopy = batch.toList()
                            _pendingFiles.update { it + batchCopy }
                            batch.clear()
                        }
                    }
                
                // Flush remaining items
                if (batch.isNotEmpty()) {
                    _pendingFiles.update { it + batch }
                }

                logger.i("=== Scan completed ===")
                _conversionStatus.postValue(ConversionUiState.Idle)
            } catch (e: Exception) {
                handleScanError(e)
            }
        }
    }

    private suspend fun setupOutputDirectory(inputDocFile: DocumentFile): DocumentFile? {
        logger.d("Setting up 'unlocked' output directory")

        // Try existing directory first, validate if it exists, otherwise create it
        val unlockedDir = inputDocFile.findFile("unlocked")?.takeIf { isValidOutputDirectory(it) }
            ?: inputDocFile.createDirectory("unlocked")

        return unlockedDir?.also {
            setOutputDir(it)
        } ?: run {
            logger.e("Failed to create 'unlocked' directory")
            withContext(Dispatchers.Main) {
                _conversionStatus.value = ConversionUiState.Error(
                    getApplication<Application>().getString(R.string.msg_create_output_fail)
                )
            }
            null
        }
    }

    

    private suspend fun getOutputBaseNames(outputDir: DocumentFile): Set<String> {
        logger.i("--- Checking unlocked directory ---")
        return if (outputDir.exists()) {
            val outputFiles = FastScanner.listFileNames(
                getApplication(),
                outputDir.uri
            )
            logger.i("Found ${outputFiles.size} files in unlocked directory:")
            outputFiles.map { it.substringBeforeLast('.') }.toSet()
        } else {
            logger.e("Unlocked directory does not exist")
            emptySet()
        }
    }

    private suspend fun handleScanError(e: Exception) {
        logger.e("Error during file scan", e)
        withContext(Dispatchers.Main) {
            _conversionStatus.value = ConversionUiState.Error("Error scanning files: ${e.message}")
        }
    }

    fun convertFiles(maxThreads: Int, cacheDir: File, selectedFiles: List<UiFile>? = null) {
        if (_conversionStatus.value is ConversionUiState.Converting) return

        val inputDir = _inputDir.value
        var outputDir = _outputDir.value
        val pendingFiles = _pendingFiles.value
        val filesToProcess = if (!selectedFiles.isNullOrEmpty()) selectedFiles else pendingFiles

        // Early validation and setup
        outputDir = ensureOutputDirectory(outputDir, inputDir) ?: return

        if (filesToProcess.isEmpty()) {
            _conversionStatus.value =
                ConversionUiState.Error(getApplication<Application>().getString(R.string.no_files_found))
            return
        }

        val optimalThreads = maxThreads

        val fileConverter = FileConverter(getApplication(), outputDir, cacheDir)

        conversionJob = viewModelScope.launch(Dispatchers.IO) {
            setConversionStatus(ConversionUiState.Converting)

            try {
                processFilesConcurrently(
                    fileConverter,
                    filesToProcess,
                    optimalThreads
                )
                setConversionStatus(ConversionUiState.Completed)
            } catch (e: Exception) {
                handleConversionError(e)
            }
        }
    }

    private fun ensureOutputDirectory(
        outputDocFile: DocumentFile?,
        inputDocFile: DocumentFile?
    ): DocumentFile? {
        val outputFile = outputDocFile?.takeIf { it.exists() }
            ?: (inputDocFile?.findFile("unlocked") ?: inputDocFile?.createDirectory("unlocked"))?.also {
                setOutputDir(it)
            }

        if (outputFile == null) {
            _conversionStatus.value =
                ConversionUiState.Error(getApplication<Application>().getString(R.string.msg_create_output_fail))
            return null
        }

        return outputFile
    }

    private suspend fun setConversionStatus(status: ConversionUiState) {
        withContext(Dispatchers.Main) {
            _conversionStatus.value = status
        }
    }

    private suspend fun processFilesConcurrently(
        fileConverter: FileConverter,
        pendingFilesList: List<UiFile>,
        maxThreads: Int
    ) {
        val filesToConvert = ArrayList(pendingFilesList)
        val semaphore = Semaphore(maxThreads)

        val deferred = filesToConvert.map { uiFile ->
            viewModelScope.async(Dispatchers.IO) {
                if (uiFile.status == FileStatus.DONE) return@async

                semaphore.withPermit {
                    val result = fileConverter.processFile(uiFile)
                    updateFileStatus(result, uiFile)
                }
            }
        }

        deferred.awaitAll()
    }

    private suspend fun updateFileStatus(result: ConversionResult, uiFile: UiFile) {
        when (result) {
            is ConversionResult.Success, ConversionResult.Skipped -> {
                uiFile.status = FileStatus.DONE
                removePendingFile(uiFile)
            }

            is ConversionResult.Failure -> {
                uiFile.status = FileStatus.ERROR
                // Force update list to refresh UI for this item
                _pendingFiles.update { it.toList() }
            }
        }
    }

    private suspend fun handleConversionError(e: Exception) {
        logger.e("Error during conversion process", e)
        setConversionStatus(ConversionUiState.Error(e.message ?: "Conversion failed"))
    }

    override fun onCleared() {
        super.onCleared()
        logger.d("MainViewModel cleared")
    }
}
