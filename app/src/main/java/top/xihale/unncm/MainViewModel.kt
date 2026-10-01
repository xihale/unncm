package top.xihale.unncm

import android.app.Application
import android.os.Looper
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
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

    private companion object {
        /** 扫描时并发读元数据的上限：MediaMetadataRetriever 为 native 调用，8 路已可跑满磁盘。 */
        private const val SCAN_CONCURRENCY = 8

        /** 每批上屏数量：兼顾 UI 渐进刷新与并发调度的尾部损耗。 */
        private const val SCAN_BATCH_SIZE = 32
    }

    private val logger = Logger.withTag("MainViewModel")

    // StateFlow for UI state
    private val _inputDir = MutableStateFlow<DocumentFile?>(null)
    val inputDir: StateFlow<DocumentFile?> = _inputDir.asStateFlow()

    private val _inputDirName = MutableStateFlow<String?>(null)
    val inputDirName: StateFlow<String?> = _inputDirName.asStateFlow()

    private val _outputDir = MutableStateFlow<DocumentFile?>(null)
    val outputDir: StateFlow<DocumentFile?> = _outputDir.asStateFlow()

    private val _outputDirName = MutableStateFlow<String?>(null)
    val outputDirName: StateFlow<String?> = _outputDirName.asStateFlow()

    private val _pendingFiles = MutableStateFlow<List<UiFile>>(emptyList())
    val pendingFiles: StateFlow<List<UiFile>> = _pendingFiles.asStateFlow()

    private val _conversionStatus = MutableStateFlow<ConversionUiState>(ConversionUiState.Idle)
    val conversionStatus: StateFlow<ConversionUiState> = _conversionStatus.asStateFlow()

    private var scanJob: Job? = null
    private var conversionJob: Job? = null

    init {
        val id = System.identityHashCode(this)
        logger.d("MainViewModel initialized. Instance ID: $id")

        // Initialize with empty list
        _pendingFiles.value = emptyList()
        _conversionStatus.value = ConversionUiState.Idle
    }

    /**
     * 设置输入目录。displayName 必须在调用方已处于后台线程时通过 [DocumentFile.getName] 取好后传入，
     * 禁止在 Compose 组合期对 DocumentFile 做 binder 查询（会造成主线程卡顿）。
     */
    fun setInputDir(documentFile: DocumentFile?, displayName: String? = null) {
        _inputDir.value = documentFile
        _inputDirName.value = displayName
    }

    fun setOutputDir(documentFile: DocumentFile?, displayName: String? = null) {
        _outputDir.value = documentFile
        _outputDirName.value = displayName
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

    fun cancelConversion() {
        conversionJob?.cancel()
        conversionJob = null
        scanJob?.cancel()
        scanJob = null
        _conversionStatus.value = ConversionUiState.Idle
    }


    fun scanFiles() {
        logger.d("scanFiles called")
        if (_conversionStatus.value is ConversionUiState.Scanning) return

        val inputDir = _inputDir.value
        if (inputDir == null) {
            logger.e("Input directory is null")
            _conversionStatus.value =
                ConversionUiState.Error(getApplication<Application>().getString(R.string.msg_invalid_input_dir))
            return
        }

        scanJob?.cancel()
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            _conversionStatus.value = ConversionUiState.Scanning
            clearPendingFiles() // Clear existing list

            try {
                val scanStart = android.os.SystemClock.elapsedRealtime()
                if (!inputDir.exists()) {
                    logger.e("Input directory invalid: uri=${inputDir.uri}")
                    _conversionStatus.value =
                        ConversionUiState.Error(
                            getApplication<Application>().getString(R.string.msg_invalid_input_dir)
                        )
                    return@launch
                }

                // 输入目录只列一遍：候选文件和 unlocked 目录条目都来自这份列表
                val listStart = android.os.SystemClock.elapsedRealtime()
                val children = FastScanner.listChildren(getApplication(), inputDir.uri)
                logger.i("scan-timing: list-input=${android.os.SystemClock.elapsedRealtime() - listStart}ms entries=${children.size}")

                val unlockedDir = resolveUnlockedDirectory(inputDir, children) ?: return@launch

                val outputStart = android.os.SystemClock.elapsedRealtime()
                val outputBaseNames = getOutputBaseNames(unlockedDir)
                logger.i("scan-timing: list-output=${android.os.SystemClock.elapsedRealtime() - outputStart}ms outputs=${outputBaseNames.size}")

                logger.i("=== Starting file scan ===")
                val candidates = children.filter { FastScanner.isAudioCandidate(it.name, it.mimeType) }
                    .map { info ->
                        val fileUri = DocumentsContract.buildDocumentUriUsingTree(inputDir.uri, info.id)
                        UiFile(fileUri, info.name, FileStatus.PENDING)
                    }

                val metadataSemaphore = Semaphore(SCAN_CONCURRENCY)
                var keptCount = 0

                // 分批并发过滤；批内按原始顺序收集结果，保证上屏顺序与文件名排序一致
                val filterStart = android.os.SystemClock.elapsedRealtime()
                candidates.chunked(SCAN_BATCH_SIZE)
                    .forEach { batch ->
                        val kept = batch
                            .map { uiFile ->
                                async {
                                    passesScanFilter(uiFile, outputBaseNames, metadataSemaphore)
                                }
                            }
                            .awaitAll()
                            .filterNotNull()

                        if (kept.isNotEmpty()) {
                            keptCount += kept.size
                            _pendingFiles.update { it + kept }
                        }
                        ensureActive()
                    }

                logger.i("scan-timing: filter=${android.os.SystemClock.elapsedRealtime() - filterStart}ms candidates=${candidates.size} kept=$keptCount")
                logger.i("=== Scan completed in ${android.os.SystemClock.elapsedRealtime() - scanStart}ms ===")
                _conversionStatus.value = ConversionUiState.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleScanError(e)
            }
        }
    }

    /**
     * 从已有的目录列表中解析 unlocked 子目录（避免为 findFile 再列一遍输入目录），
     * 不存在时创建。
     */
    private suspend fun resolveUnlockedDirectory(
        inputDir: DocumentFile,
        children: List<FastScanner.FileInfo>
    ): DocumentFile? {
        val existing = children.firstOrNull { it.name == "unlocked" && it.isDirectory }?.let { info ->
            val documentUri = DocumentsContract.buildDocumentUriUsingTree(inputDir.uri, info.id)
            DocumentFile.fromTreeUri(getApplication(), documentUri)?.takeIf { it.isDirectory }
        }
        val unlockedDir = existing ?: inputDir.createDirectory("unlocked")

        return unlockedDir?.also {
            setOutputDir(it, it.name)
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

    /**
     * 过滤待处理文件：NCM 文件只查输出目录重名（内存集合，瞬时）；
     * 普通音频文件需要读元数据判断是否缺标签/封面，走信号量限制并发。
     */
    private suspend fun passesScanFilter(
        uiFile: UiFile,
        outputBaseNames: Set<String>,
        metadataSemaphore: Semaphore
    ): UiFile? {
        val isNcm = uiFile.fileName.endsWith(".ncm", ignoreCase = true)
        val baseName = uiFile.fileName.substringBeforeLast('.')

        return if (isNcm) {
            // Skip NCM files that are already in output directory
            if (baseName in outputBaseNames) null else uiFile
        } else {
            metadataSemaphore.withPermit {
                val needsProcessing = MediaMetadataRetrieverHelper.probeNeedsProcessing(
                    getApplication(),
                    uiFile.uri,
                    uiFile.fileName
                )
                if (needsProcessing) uiFile else null
            }
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

        conversionJob = viewModelScope.launch(Dispatchers.IO) {
            // DocumentFile.exists()/findFile() 都是 binder 查询，必须留在 IO 线程，
            // 避免点击“开始转换”时阻塞主线程。
            val outputDir = ensureOutputDirectory(_outputDir.value, _inputDir.value)
                ?: return@launch

            val filesToProcess = if (!selectedFiles.isNullOrEmpty()) selectedFiles else _pendingFiles.value
            if (filesToProcess.isEmpty()) {
                _conversionStatus.value =
                    ConversionUiState.Error(getApplication<Application>().getString(R.string.no_files_found))
                return@launch
            }

            setConversionStatus(ConversionUiState.Converting)

            try {
                processFilesConcurrently(
                    FileConverter(getApplication(), outputDir, cacheDir),
                    filesToProcess,
                    maxThreads
                )
                setConversionStatus(ConversionUiState.Completed)
            } catch (e: CancellationException) {
                throw e
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
                setOutputDir(it, it.name)
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
