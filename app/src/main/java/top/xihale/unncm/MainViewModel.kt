package top.xihale.unncm

import android.app.Application
import android.net.Uri
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
import top.xihale.unncm.utils.Logger
import java.io.File

sealed class ConversionUiState {
    object Idle : ConversionUiState()
    object Converting : ConversionUiState()
    data class Completed(val successCount: Int, val errorCount: Int) : ConversionUiState()
    data class Error(val message: String) : ConversionUiState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val logger = Logger.withTag("MainViewModel")

    // LiveData for UI state
    private val _inputDocumentFile = MutableLiveData<DocumentFile?>()
    val inputDocumentFile: LiveData<DocumentFile?> = _inputDocumentFile

    private val _outputDocumentFile = MutableLiveData<DocumentFile?>()
    val outputDocumentFile: LiveData<DocumentFile?> = _outputDocumentFile

    private val _pendingFiles = MutableLiveData<MutableList<UiFile>>()
    val pendingFiles: LiveData<MutableList<UiFile>> = _pendingFiles

    private val _isScanning = MutableLiveData<Boolean>()
    val isScanning: LiveData<Boolean> = _isScanning

    private val _conversionStatus = MutableLiveData<ConversionUiState>()
    val conversionStatus: LiveData<ConversionUiState> = _conversionStatus

    private var scanJob: Job? = null
    private var conversionJob: Job? = null

    init {
        val id = System.identityHashCode(this)
        logger.d("MainViewModel initialized. Instance ID: $id")
        
        // Initialize with empty list
        _pendingFiles.value = mutableListOf()
        _isScanning.value = false
        _conversionStatus.value = ConversionUiState.Idle
    }

    fun setInputDocumentFile(documentFile: DocumentFile?) {
        viewModelScope.launch(Dispatchers.Main) {
            _inputDocumentFile.value = documentFile
            logger.d("Input document file set: ${documentFile?.name}")
        }
    }

    // Synchronous version - only call from main thread
    fun setInputDocumentFileSync(documentFile: DocumentFile?) {
        _inputDocumentFile.value = documentFile
        logger.d("Input document file set sync: ${documentFile?.name}")
    }

    fun setOutputDocumentFile(documentFile: DocumentFile?) {
        viewModelScope.launch(Dispatchers.Main) {
            _outputDocumentFile.value = documentFile
            logger.d("Output document file set: ${documentFile?.name}")
        }
    }

    fun setPendingFiles(files: List<UiFile>) {
        viewModelScope.launch(Dispatchers.Main) {
            _pendingFiles.value = files.toMutableList()
            logger.d("Pending files updated: ${files.size} files")
        }
    }

    fun addPendingFile(file: UiFile) {
        viewModelScope.launch(Dispatchers.Main) {
            val currentList = _pendingFiles.value ?: mutableListOf()
            currentList.add(file)
            _pendingFiles.value = currentList
            logger.d("Added pending file: ${file.fileName}")
        }
    }

    fun removePendingFile(file: UiFile) {
        viewModelScope.launch(Dispatchers.Main) {
            val currentList = _pendingFiles.value ?: mutableListOf()
            currentList.remove(file)
            _pendingFiles.value = currentList
            logger.d("Removed pending file: ${file.fileName}")
        }
    }

    fun clearPendingFiles() {
        viewModelScope.launch(Dispatchers.Main) {
            _pendingFiles.value = mutableListOf()
            logger.d("Pending files cleared")
        }
    }

    fun setScanning(isScanning: Boolean) {
        viewModelScope.launch(Dispatchers.Main) {
            _isScanning.value = isScanning
            logger.d("Scanning state changed: $isScanning")
        }
    }

    fun resetConversionStatus() {
        _conversionStatus.value = ConversionUiState.Idle
    }

        
    private fun isValidOutputDirectory(docFile: DocumentFile): Boolean {
        if (!docFile.exists() || !docFile.isDirectory) return false
        try {
             val testName = ".test_${System.currentTimeMillis()}"
             val testFile = docFile.createFile("text/plain", testName)
             if (testFile != null) {
                 testFile.delete()
                 return true
             }
        } catch (e: Exception) {
             // Ignore
        }
        return false
    }

    fun scanFiles() {
        logger.d("scanFiles called")
        if (_isScanning.value == true) return

        val inputDocFile = _inputDocumentFile.value
        logger.d("Input document file check: file=${inputDocFile?.name}, uri=${inputDocFile?.uri}, exists=${inputDocFile?.exists()}")

        if (inputDocFile == null) {
            logger.e("Input document file is null")
            _conversionStatus.value = ConversionUiState.Error(getApplication<Application>().getString(R.string.msg_invalid_input_dir))
            return
        }

        if (!inputDocFile.exists()) {
            logger.e("Input directory does not exist: ${inputDocFile.uri}")
            _conversionStatus.value = ConversionUiState.Error(getApplication<Application>().getString(R.string.msg_invalid_input_dir))
            return
        }

        scanJob?.cancel()
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            setScanning(true)
            try {
                logger.d("Setting up 'unlocked' output directory")
                var unlockedDir = inputDocFile.findFile("unlocked")

                if (unlockedDir != null && !isValidOutputDirectory(unlockedDir)) {
                    logger.w("Found 'unlocked' but it seems invalid/stale. Ignoring.")
                    unlockedDir = null
                }

                if (unlockedDir == null) {
                     unlockedDir = inputDocFile.createDirectory("unlocked")
                }

                if (unlockedDir != null) {
                    setOutputDocumentFile(unlockedDir)
                } else {
                    logger.e("Failed to create 'unlocked' directory")
                     withContext(Dispatchers.Main) {
                        setScanning(false)
                        _conversionStatus.value = ConversionUiState.Error(getApplication<Application>().getString(R.string.msg_create_output_fail))
                    }
                    return@launch
                }

                logger.i("=== Starting file scan ===")
                logger.i("Input directory: ${inputDocFile.name}")
                logger.i("Output directory exists: ${outputDocumentFile.value?.exists()}")

                // Scan input directory
                logger.i("--- Scanning input directory ---")
                val audioFiles = top.xihale.unncm.utils.FastScanner.scanAudioFiles(getApplication(), inputDocFile.uri)
                logger.i("Found ${audioFiles.size} total audio files in input directory")

                // Separate file types
                val allNcmFiles = audioFiles.filter { it.fileName.endsWith(".ncm", ignoreCase = true) }
                val allOtherAudioFiles = audioFiles.filter { !it.fileName.endsWith(".ncm", ignoreCase = true) }

                logger.i("File type breakdown:")
                logger.i("  NCM files: ${allNcmFiles.size}")
                logger.i("  Other audio files: ${allOtherAudioFiles.size}")

                // Log file names for debugging
                if (allNcmFiles.isNotEmpty()) {
                    logger.i("NCM files found:")
                    allNcmFiles.take(10).forEach { file ->
                        logger.i("  - ${file.fileName}")
                    }
                    if (allNcmFiles.size > 10) {
                        logger.i("  ... and ${allNcmFiles.size - 10} more NCM files")
                    }
                }

                if (allOtherAudioFiles.isNotEmpty()) {
                    logger.i("Other audio files found:")
                    allOtherAudioFiles.take(10).forEach { file ->
                        logger.i("  - ${file.fileName}")
                    }
                    if (allOtherAudioFiles.size > 10) {
                        logger.i("  ... and ${allOtherAudioFiles.size - 10} more audio files")
                    }
                }

                // Check unlocked directory
                logger.i("--- Checking unlocked directory ---")
                val outputBaseNames = if (outputDocumentFile.value?.exists() == true) {
                    val outputFiles = top.xihale.unncm.utils.FastScanner.listFileNames(getApplication(), outputDocumentFile.value!!.uri)
                    logger.i("Found ${outputFiles.size} files in unlocked directory:")
                    outputFiles.take(10).forEach { fileName ->
                        logger.i("  - $fileName")
                    }
                    if (outputFiles.size > 10) {
                        logger.i("  ... and ${outputFiles.size - 10} more files")
                    }
                    outputFiles.map { it.substringBeforeLast('.') }.toSet()
                } else {
                    logger.w("Unlocked directory does not exist")
                    emptySet()
                }

                // Filter NCM files
                logger.i("--- Filtering NCM files ---")
                val ncmFiles = allNcmFiles.filter {
                    val baseName = it.fileName.substringBeforeLast('.')
                    val shouldProcess = baseName !in outputBaseNames
                    if (!shouldProcess) {
                        logger.d("  [SKIP] NCM file already converted: ${it.fileName}")
                    } else {
                        logger.d("  [KEEP] NCM file needs conversion: ${it.fileName}")
                    }
                    shouldProcess
                }

                // Keep all other audio files for metadata checking
                val otherAudioFiles = allOtherAudioFiles
                logger.d("  [KEEP] All ${otherAudioFiles.size} other audio files for metadata checking")

                // Summary
                logger.i("--- Scan summary ---")
                logger.i("Total files found: ${audioFiles.size}")
                logger.i("NCM files to process: ${ncmFiles.size} (filtered from ${allNcmFiles.size})")
                logger.i("Other audio files to process: ${otherAudioFiles.size}")
                logger.i("Total files in processing list: ${ncmFiles.size + otherAudioFiles.size}")

                // Combine files for processing
                val allToProcess = ncmFiles + otherAudioFiles
                logger.i("=== Scan completed, starting processing ===")
                
                logger.i("Total files to process: ${allToProcess.size}")

                withContext(Dispatchers.Main) {
                    setPendingFiles(allToProcess)
                    setScanning(false)
                }
            } catch (e: Exception) {
                logger.e("Error during file scan", e)
                withContext(Dispatchers.Main) {
                    setScanning(false)
                    _conversionStatus.value = ConversionUiState.Error("Error scanning files: ${e.message}")
                }
            }
        }
    }

    fun convertFiles(maxThreads: Int, cacheDir: File) {
        if (_conversionStatus.value is ConversionUiState.Converting) return
        
        val inputDocFile = _inputDocumentFile.value
        var outputDocFile = _outputDocumentFile.value
        val pendingFilesList = _pendingFiles.value ?: emptyList()

        if (outputDocFile == null || !outputDocFile.exists()) {
             val unlocked = inputDocFile?.findFile("unlocked")
                            ?: inputDocFile?.createDirectory("unlocked")
             unlocked?.let { setOutputDocumentFile(it); outputDocFile = it }
        }

        if (outputDocFile == null) {
            _conversionStatus.value = ConversionUiState.Error(getApplication<Application>().getString(R.string.msg_create_output_fail))
            return
        }

        if (pendingFilesList.isEmpty()) {
            _conversionStatus.value = ConversionUiState.Error(getApplication<Application>().getString(R.string.no_files_found))
            return
        }
        
        val fileConverter = FileConverter(getApplication(), outputDocFile!!, cacheDir)
        
        conversionJob = viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                _conversionStatus.value = ConversionUiState.Converting
            }

            try {
                val filesToConvert = ArrayList(pendingFilesList)
                val semaphore = Semaphore(maxThreads)
                var successCount = 0
                var errorCount = 0

                val deferreds = filesToConvert.map { uiFile ->
                    async(Dispatchers.IO) {
                        if (uiFile.status == FileStatus.DONE) {
                            return@async
                        }

                        semaphore.withPermit {
                            val result = fileConverter.processFile(uiFile)
                            
                            withContext(Dispatchers.Main) {
                                when (result) {
                                    is ConversionResult.Success, ConversionResult.Skipped -> {
                                        successCount++
                                        uiFile.status = FileStatus.DONE
                                        removePendingFile(uiFile)
                                    }
                                    is ConversionResult.Failure -> {
                                        errorCount++
                                        uiFile.status = FileStatus.ERROR
                                        // Force update list to refresh UI for this item
                                        _pendingFiles.value = _pendingFiles.value
                                    }
                                }
                            }
                        }
                    }
                }

                deferreds.awaitAll()

                withContext(Dispatchers.Main) {
                     _conversionStatus.value = ConversionUiState.Completed(successCount, errorCount)
                }
            } catch (e: Exception) {
                logger.e("Error during conversion process", e)
                withContext(Dispatchers.Main) {
                    _conversionStatus.value = ConversionUiState.Error(e.message ?: "Conversion failed")
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        logger.d("MainViewModel cleared")
    }
}
