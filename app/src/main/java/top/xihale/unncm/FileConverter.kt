package top.xihale.unncm

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.xihale.unncm.utils.Logger
import java.io.File
import java.io.InputStream

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

    suspend fun processFile(uiFile: UiFile): ConversionResult = withContext(Dispatchers.IO) {
        val fileName = uiFile.fileName
        val baseName = fileName.substringBeforeLast('.')
        val isNcm = fileName.endsWith(".ncm", ignoreCase = true)

        logger.i("=== Processing file: $fileName ===")
        logger.i("File type: ${if (isNcm) "NCM (decryption)" else "Regular audio (metadata check)"}")
        logger.i("Base name: $baseName")

        try {
            // For non-NCM files, we need to read the entire stream first for metadata checking
            if (isNcm) {
                logger.i("--- Processing NCM file (decryption) ---")
                context.contentResolver.openInputStream(uiFile.uri)?.use { inputStream ->
                    java.io.BufferedInputStream(inputStream).use { bufferedInput ->
                        val result = processNcmFile(bufferedInput, baseName, fileName)
                        logger.i("NCM processing completed for: $fileName -> $result")
                        result
                    }
                } ?: run {
                    logger.e("Failed to open input stream for NCM file: $fileName")
                    return@withContext ConversionResult.Failure(Exception("Failed to open input stream"))
                }
            } else {
                logger.i("--- Processing regular audio file (metadata check) ---")
                context.contentResolver.openInputStream(uiFile.uri)?.use { inputStream ->
                    val streamBytes = inputStream.readBytes()
                    logger.d("Read ${streamBytes.size} bytes from: $fileName")
                    java.io.BufferedInputStream(streamBytes.inputStream()).use { bufferedInput ->
                        val result = processRegularAudio(bufferedInput, baseName, fileName, uiFile)
                        logger.i("Audio metadata processing completed for: $fileName -> $result")
                        result
                    }
                } ?: run {
                    logger.e("Failed to open input stream for audio file: $fileName")
                    return@withContext ConversionResult.Failure(Exception("Failed to open input stream"))
                }
            }
        } catch (e: Exception) {
            logger.e("Error processing file: $fileName", e)
            ConversionResult.Failure(e)
        }
    }

    private suspend fun processNcmFile(
        inputStream: InputStream,
        baseName: String,
        originalName: String
    ): ConversionResult {
        logger.d("Decrypting NCM: $originalName")
        val decryptor = NcmDecryptor(inputStream)
        val ncmInfo = decryptor.parseHeader() 
            ?: return ConversionResult.Failure(Exception("Failed to parse NCM header"))

        // 1. Fetch Metadata (Always needed for NCM to get better tags)
        val apiMetadata = fetchMetadata(baseName, fetchCover = true, fetchLyrics = true)
        
        // 2. Merge Metadata
        val finalMetadata = mergeMetadata(ncmInfo, apiMetadata, baseName)

        // 3. Write Output
        val ext = ncmInfo.format
        val outputName = "$baseName.$ext"
        
        return writeOutput(outputName, finalMetadata, ext) { output ->
            decryptor.decryptAudio(output)
        }
    }

    private suspend fun processRegularAudio(
        inputStream: InputStream,
        baseName: String,
        originalName: String,
        uiFile: UiFile
    ): ConversionResult {
        val ext = originalName.substringAfterLast('.', "")
        logger.i("--- Starting metadata analysis for: $originalName ---")

        return try {
            // Analyze metadata using streaming (no full file in RAM)
            val analysis = AudioMetadataProcessor.analyzeMetadata(inputStream, originalName, cacheDir)
            
            val hasCompleteMetadata = analysis.hasCompleteMetadata
            val hasLyrics = analysis.hasLyrics
            val hasCover = analysis.hasCover
            
            val needsMetadata = !hasCompleteMetadata
            val needsLyrics = !hasLyrics
            val needsCover = !hasCover

            logger.i("Metadata analysis results:")
            logger.i("  Complete metadata: $hasCompleteMetadata")
            logger.i("  Has lyrics: $hasLyrics")
            logger.i("  Has cover art: $hasCover")

            if (!needsMetadata && !needsLyrics && !needsCover) {
                logger.i("✓ File already has complete metadata, skipping processing")
                return ConversionResult.Success
            }

            logger.i("⚠ File needs metadata enhancement")

            // Fetch needed metadata from API
            logger.d("--- Fetching metadata from API ---")
            val apiMetadata = fetchMetadata(baseName, fetchCover = needsCover, fetchLyrics = needsLyrics)

            if (apiMetadata == null) {
                logger.w("⚠ Failed to fetch metadata from API for: $originalName")
                return ConversionResult.Success
            }

            // Merge metadata
            val existingTags = analysis.existingTags
            val finalMetadata = MusicMetadata(
                id = 0,
                title = if (needsMetadata && !apiMetadata.title.isNullOrBlank()) apiMetadata.title else existingTags.title,
                artist = if (needsMetadata && !apiMetadata.artist.isNullOrBlank()) apiMetadata.artist else existingTags.artist,
                album = if (needsMetadata && !apiMetadata.album.isNullOrBlank()) apiMetadata.album else existingTags.album,
                coverUrl = null,
                duration = 0,
                lyrics = if (needsLyrics) apiMetadata.lyrics else existingTags.lyrics,
                coverData = if (needsCover) apiMetadata.coverData else null
            )

            // Write metadata to file
            logger.d("--- Writing metadata to file ---")
            return writeMetadataToOriginalFile(uiFile, finalMetadata, ext)

        } catch (e: Exception) {
            logger.e("✗ Error processing regular audio file: $originalName", e)
            ConversionResult.Failure(e)
        }
    }


    private suspend fun fetchMetadata(keyword: String, fetchCover: Boolean, fetchLyrics: Boolean): MusicMetadata? {
        return NeteaseApiService.getCompleteMetadata(keyword, fetchCover, fetchLyrics).getOrNull()
    }

    private fun mergeMetadata(ncmInfo: NcmInfo, apiMetadata: MusicMetadata?, fallbackTitle: String): MusicMetadata {
        if (apiMetadata != null) {
            // If API returned cover, use it. If not, fallback to NCM embedded cover.
            val cover = apiMetadata.coverData ?: ncmInfo.cover
            return apiMetadata.copy(coverData = cover)
        }
        // Fallback to NCM info
        return MusicMetadata(
            id = 0,
            title = ncmInfo.title ?: fallbackTitle,
            artist = ncmInfo.artist.joinToString("/") ?: "",
            album = ncmInfo.album ?: "",
            coverUrl = null,
            duration = 0,
            lyrics = null,
            coverData = ncmInfo.cover
        )
    }
    
    private data class SimpleTags(val title: String, val artist: String, val album: String)
    
    private fun readExistingTags(file: File): SimpleTags {
        return try {
            val audio = org.jaudiotagger.audio.AudioFileIO.read(file)
            val tag = audio.tag
            if (tag != null) {
                SimpleTags(
                    tag.getFirst(org.jaudiotagger.tag.FieldKey.TITLE) ?: "",
                    tag.getFirst(org.jaudiotagger.tag.FieldKey.ARTIST) ?: "",
                    tag.getFirst(org.jaudiotagger.tag.FieldKey.ALBUM) ?: ""
                )
            } else SimpleTags("", "", "")
        } catch (e: Exception) {
            SimpleTags("", "", "")
        }
    }

    private fun writeOutput(
        fileName: String, 
        metadata: MusicMetadata, 
        format: String,
        writeAction: (java.io.OutputStream) -> Unit
    ): ConversionResult {
        val outputFile = outputDir.findFile(fileName) ?: outputDir.createFile("audio/*", fileName)
        
        if (outputFile == null) {
             return ConversionResult.Failure(Exception("Could not create output file: $fileName"))
        }

        return try {
            context.contentResolver.openOutputStream(outputFile.uri)?.let { stream ->
                java.io.BufferedOutputStream(stream).use { bufferedOut ->
                    AudioMetadataProcessor.processAudioData(
                        writer = writeAction,
                        format = format,
                        metadata = metadata,
                        outputStream = bufferedOut,
                        cacheDir = cacheDir
                    )
                }
            }
            ConversionResult.Success
        } catch (e: Exception) {
            // Try to cleanup partial file
            try { outputFile.delete() } catch (x: Exception) {} 
            ConversionResult.Failure(e)
        }
    }

    private fun writeMetadataToOriginalFile(
        uiFile: UiFile,
        metadata: MusicMetadata,
        format: String
    ): ConversionResult {
        val tempSource = File(cacheDir, "source_${System.currentTimeMillis()}_${uiFile.fileName}")
        try {
            // 1. Copy original file to temp source
            context.contentResolver.openInputStream(uiFile.uri)?.use { input ->
                tempSource.outputStream().buffered().use { output ->
                    input.copyTo(output)
                }
            } ?: return ConversionResult.Failure(Exception("Could not open input stream"))

            // 2. Process and write back to original
            context.contentResolver.openOutputStream(uiFile.uri, "wt")?.use { outputStream ->
                java.io.BufferedOutputStream(outputStream).use { bufferedOut ->
                    AudioMetadataProcessor.processAudioData(
                        writer = { output ->
                            tempSource.inputStream().buffered().use { input ->
                                input.copyTo(output)
                            }
                        },
                        format = format,
                        metadata = metadata,
                        outputStream = bufferedOut,
                        cacheDir = cacheDir
                    )
                }
            } ?: return ConversionResult.Failure(Exception("Could not open original file for writing"))

            logger.i("Successfully updated metadata for: ${uiFile.fileName}")
            return ConversionResult.Success
        } catch (e: Exception) {
            logger.e("Failed to write metadata to original file: ${uiFile.fileName}", e)
            return ConversionResult.Failure(e)
        } finally {
            tempSource.delete()
        }
    }


}