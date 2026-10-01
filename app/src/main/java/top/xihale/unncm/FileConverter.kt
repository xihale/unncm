package top.xihale.unncm

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.xihale.unncm.utils.FastScanner
import top.xihale.unncm.utils.Logger
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

sealed interface ConversionResult {
    data object Success : ConversionResult
    data object Skipped : ConversionResult
    data class Failure(val error: Throwable) : ConversionResult
}

class FileConverter(
    private val context: Context,
    private val outputDir: DocumentFile,
    private val cacheDir: File
) {
    private val logger = Logger.withTag("FileConverter")

    // 输出文件名去重集合：避免每写一个文件都做一次全目录 SAF 列表查询（O(n²)）
    private val knownOutputNames: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val outputNamesSeedMutex = Mutex()

    @Volatile
    private var outputNamesSeeded = false

    /**
     * 解析输出文件：首次调用时做一次全目录列举填充集合，之后纯内存查重；
     * 只有重名（理论上扫描已过滤）才回退到 findFile 精确查询。
     */
    private suspend fun resolveOutputFile(fileName: String): DocumentFile? {
        if (!outputNamesSeeded) {
            outputNamesSeedMutex.withLock {
                if (!outputNamesSeeded) {
                    FastScanner.listFileNames(context, outputDir.uri).forEach { knownOutputNames.add(it) }
                    outputNamesSeeded = true
                }
            }
        }

        if (knownOutputNames.contains(fileName)) {
            return outputDir.findFile(fileName)
        }
        return outputDir.createFile("audio/*", fileName)?.also { knownOutputNames.add(fileName) }
    }

    suspend fun processFile(uiFile: UiFile): ConversionResult = withContext(Dispatchers.IO) {
        val fileName = uiFile.fileName
        val baseName = fileName.substringBeforeLast('.')
        val isNcm = fileName.endsWith(".ncm", ignoreCase = true)

        logFileProcessingStart(fileName, isNcm, baseName)

        try {
            when {
                isNcm -> processNcmStream(uiFile.uri, baseName, fileName)
                else -> processRegularAudioStream(uiFile, baseName, fileName)
            }
        } catch (e: Exception) {
            logger.e("Error processing file: $fileName", e)
            ConversionResult.Failure(e)
        }
    }

    private fun logFileProcessingStart(fileName: String, isNcm: Boolean, baseName: String) {
        logger.i("=== Processing file: $fileName ===")
        logger.i("File type: ${if (isNcm) "NCM (decryption)" else "Regular audio (metadata check)"}")
        logger.i("Base name: $baseName")
    }

    private suspend fun processNcmStream(uri: Uri, baseName: String, fileName: String): ConversionResult {
        logger.i("--- Processing NCM file (decryption) ---")

        val inputDescriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: run {
            logger.e("Failed to open input descriptor for NCM file: $fileName")
            return ConversionResult.Failure(Exception("Failed to open input file descriptor"))
        }

        return inputDescriptor.use { descriptor ->
            val result = processNcmFile(descriptor, baseName, fileName)
            logger.i("NCM processing completed for: $fileName -> $result")
            result
        }
    }

    private suspend fun processRegularAudioStream(uiFile: UiFile, baseName: String, fileName: String): ConversionResult {
        logger.i("--- Processing regular audio file (metadata check) ---")

        val inputStream = context.contentResolver.openInputStream(uiFile.uri) ?: run {
            logger.e("Failed to open input stream for audio file: $fileName")
            return ConversionResult.Failure(Exception("Failed to open input stream"))
        }

        return inputStream.use { stream ->
            // Use streaming approach to avoid loading entire file into memory
            java.io.BufferedInputStream(stream, 256 * 1024).use { bufferedInput ->
                val result = enhanceRegularAudio(bufferedInput, baseName, fileName, uiFile)
                logger.i("Audio metadata processing completed for: $fileName -> $result")
                result
            }
        }
    }

    private suspend fun processNcmFile(
        inputDescriptor: ParcelFileDescriptor,
        baseName: String,
        originalName: String
    ): ConversionResult {
        logger.d("Decrypting NCM: $originalName")
        var decryptedFile = File.createTempFile("unncm_decrypted_", ".tmp", cacheDir)

        return try {
            val outputDescriptor = ParcelFileDescriptor.open(
                decryptedFile,
                ParcelFileDescriptor.MODE_WRITE_ONLY or
                    ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE
            )
            val ncmInfo = outputDescriptor.use { descriptor ->
                NativeNcmCore.decrypt(inputDescriptor.fd, descriptor.fd)
            }

            // Fetch richer metadata after native decryption has validated the input.
            // 歌词无法内嵌于 NCM，必须始终请求；封面在内嵌时直接复用，省一次下载。
            val apiMetadata = fetchMetadata(baseName, fetchCover = ncmInfo.cover == null, fetchLyrics = true)
            val finalMetadata = mergeMetadata(ncmInfo, apiMetadata, baseName)

            val ext = ncmInfo.format
            val outputName = "$baseName.$ext"
            val typedAudioFile = File(cacheDir, "${decryptedFile.nameWithoutExtension}.$ext")
            if (!decryptedFile.renameTo(typedAudioFile)) {
                throw IOException("Could not prepare decrypted audio for tagging")
            }
            decryptedFile = typedAudioFile

            writeOutput(
                outputName,
                decryptedFile,
                finalMetadata.metadata,
                finalMetadata.lyrics,
                finalMetadata.coverData
            )
        } finally {
            if (!decryptedFile.delete()) {
                logger.w("Failed to delete decrypted temporary file: ${decryptedFile.name}")
            }
        }
    }

    private suspend fun enhanceRegularAudio(
        inputStream: InputStream,
        baseName: String,
        originalName: String,
        uiFile: UiFile
    ): ConversionResult {
        val ext = originalName.substringAfterLast('.', "")
        logger.i("--- Enhancing metadata/lyrics for: $originalName ---")

        return try {
            // 只读头部判断已有标签，避免把整个音频文件拷贝到缓存再分析
            val analysis = AudioMetadataProcessor.analyzeMetadataLightweight(inputStream, originalName, cacheDir)
            val needs = RegularAudioNeeds(
                needTitle = analysis.existingTags.title.isBlank(),
                needArtist = analysis.existingTags.artist.isBlank(),
                needAlbum = analysis.existingTags.album.isBlank(),
                needLyrics = !analysis.hasLyrics,
                needCover = !analysis.hasCover
            )

            if (!needs.needsAnyUpdate) {
                logger.i("No metadata enhancement needed for: $originalName")
                return ConversionResult.Success
            }

            val apiMetadata = fetchMetadata(
                keyword = baseName,
                fetchCover = needs.needCover,
                fetchLyrics = needs.needLyrics
            )

            if (apiMetadata == null) {
                logger.w("Failed to fetch metadata from API for: $originalName, skipping enhancement.")
                return ConversionResult.Success
            }

            val finalMetadata = mergeRegularAudioMetadata(analysis.existingTags, apiMetadata.metadata, needs)
            val lyricsToWrite = if (needs.needLyrics) apiMetadata.lyrics?.takeIf { it.isNotBlank() } else null
            val coverToWrite = if (needs.needCover) apiMetadata.coverData?.takeIf { it.isNotEmpty() } else null
            val metadataChanged = finalMetadata != analysis.existingTags

            if (!metadataChanged && lyricsToWrite == null && coverToWrite == null) {
                logger.i("No writable new metadata fetched for: $originalName")
                return ConversionResult.Success
            }

            val result = updateAudioFileMetadata(uiFile, finalMetadata, lyricsToWrite, coverToWrite, ext)
            logger.i("Metadata enhancement completed for: $originalName -> $result")
            result
        } catch (e: Exception) {
            logger.e("Error enhancing regular audio file: $originalName", e)
            ConversionResult.Failure(e)
        }
    }

    // Unified helper function for updating metadata in an existing file
    private suspend fun updateAudioFileMetadata(
        uiFile: UiFile,
        metadata: MusicMetadata,
        lyrics: String?,
        coverData: ByteArray?,
        format: String
    ): ConversionResult {
        val tempSource = File(cacheDir, "source_${System.currentTimeMillis()}_${uiFile.fileName}")

        return try {
            // Copy original to temp
            val inputStream = context.contentResolver.openInputStream(uiFile.uri) 
                ?: return ConversionResult.Failure(Exception("Could not open input stream for ${uiFile.fileName}"))
            
            inputStream.use { input ->
                tempSource.outputStream().buffered().use { output ->
                    input.copyTo(output)
                }
            }

            val outputStream = context.contentResolver.openOutputStream(uiFile.uri, "wt")
                ?: return ConversionResult.Failure(Exception("Could not open original file for writing: ${uiFile.fileName}"))

            outputStream.use { stream ->
                java.io.BufferedOutputStream(stream).use { bufferedOut ->
                    AudioMetadataProcessor.processAudioData(
                        writer = { output ->
                            tempSource.inputStream().buffered().use { input ->
                                input.copyTo(output)
                            }
                        },
                        format = format,
                        metadata = metadata,
                        lyrics = lyrics,
                        coverData = coverData,
                        outputStream = bufferedOut,
                        cacheDir = cacheDir
                    ).getOrThrow()
                }
            }

            logger.i("Successfully updated metadata for: ${uiFile.fileName}")
            ConversionResult.Success
        } catch (e: Exception) {
            logger.e("Failed to update metadata for: ${uiFile.fileName}", e)
            ConversionResult.Failure(e)
        } finally {
            tempSource.delete()
        }
    }


    private data class RegularAudioNeeds(
        val needTitle: Boolean,
        val needArtist: Boolean,
        val needAlbum: Boolean,
        val needLyrics: Boolean,
        val needCover: Boolean
    ) {
        val needsAnyUpdate: Boolean
            get() = needTitle || needArtist || needAlbum || needLyrics || needCover
    }

    private suspend fun fetchMetadata(keyword: String, fetchCover: Boolean, fetchLyrics: Boolean): ExtendedMusicMetadata? {
        return NeteaseApiService.getCompleteMetadata(keyword, fetchCover, fetchLyrics).getOrNull()
    }

    private fun mergeRegularAudioMetadata(
        existing: MusicMetadata,
        fromApi: MusicMetadata,
        needs: RegularAudioNeeds
    ): MusicMetadata {
        fun keepOrFill(needsField: Boolean, oldValue: String, newValue: String): String {
            if (!needsField) return oldValue
            return if (newValue.isNotBlank()) newValue else oldValue
        }

        return MusicMetadata(
            title = keepOrFill(needs.needTitle, existing.title, fromApi.title),
            artist = keepOrFill(needs.needArtist, existing.artist, fromApi.artist),
            album = keepOrFill(needs.needAlbum, existing.album, fromApi.album)
        )
    }

    private fun mergeMetadata(ncmInfo: NcmInfo, apiMetadata: ExtendedMusicMetadata?, fallbackTitle: String): ExtendedMusicMetadata {
        if (apiMetadata != null) {
            // 封面下载被跳过或失败时回退 NCM 内嵌封面
            if (apiMetadata.coverData == null && ncmInfo.cover != null) {
                return apiMetadata.copy(coverData = ncmInfo.cover)
            }
            return apiMetadata
        }
        // Fallback to NCM info
        val ncmMetadata = MusicMetadata(
            title = ncmInfo.title ?: fallbackTitle,
            artist = ncmInfo.artist.joinToString("/"),
            album = ncmInfo.album ?: ""
        )

        return ExtendedMusicMetadata(
            metadata = ncmMetadata,
            id = 0,
            coverUrl = null,
            duration = 0,
            lyrics = null,
            coverData = ncmInfo.cover
        )
    }

    private suspend fun writeOutput(
        fileName: String,
        sourceFile: File,
        metadata: MusicMetadata,
        lyrics: String? = null,
        coverData: ByteArray? = null
    ): ConversionResult {
        val outputFile = resolveOutputFile(fileName)
            ?: return ConversionResult.Failure(Exception("Could not create output file: $fileName"))

        return try {
            writeAudioDataToFile(outputFile, sourceFile, metadata, lyrics, coverData)
            ConversionResult.Success
        } catch (e: Exception) {
            cleanupPartialFile(outputFile)
            ConversionResult.Failure(e)
        }
    }

    private fun writeAudioDataToFile(
        outputFile: DocumentFile,
        sourceFile: File,
        metadata: MusicMetadata,
        lyrics: String? = null,
        coverData: ByteArray? = null
    ) {
        val stream = context.contentResolver.openOutputStream(outputFile.uri)
            ?: throw Exception("Could not open output stream")

        stream.use { outputStream ->
            java.io.BufferedOutputStream(outputStream).use { bufferedOut ->
                AudioMetadataProcessor.processExistingAudioFile(
                    audioFile = sourceFile,
                    metadata = metadata,
                    lyrics = lyrics,
                    coverData = coverData,
                    outputStream = bufferedOut
                ).getOrThrow()
            }
        }
    }

    private fun cleanupPartialFile(outputFile: DocumentFile) {
        try { outputFile.delete() } catch (e: Exception) {
            logger.w("Failed to cleanup partial file: ${outputFile.name}", e)
        }
    }

}
