package top.xihale.unncm

import android.content.Context
import android.net.Uri
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

        val inputStream = context.contentResolver.openInputStream(uri) ?: run {
            logger.e("Failed to open input stream for NCM file: $fileName")
            return ConversionResult.Failure(Exception("Failed to open input stream"))
        }

        return inputStream.use { stream ->
            java.io.BufferedInputStream(stream).use { bufferedInput ->
                val result = processNcmFile(bufferedInput, baseName, fileName)
                logger.i("NCM processing completed for: $fileName -> $result")
                result
            }
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

        return writeOutput(outputName, finalMetadata.metadata, ext, apiMetadata?.lyrics, apiMetadata?.coverData) { output ->
            decryptor.decryptAudio(output)
        }
    }

    private suspend fun enhanceRegularAudio(
        inputStream: InputStream, // Kept for consistency, but new logic rereads from uiFile.uri
        baseName: String,
        originalName: String,
        uiFile: UiFile
    ): ConversionResult {
        val ext = originalName.substringAfterLast('.', "")
        logger.i("--- Enhancing metadata/lyrics for: $originalName ---")

        return try {
            // 1. Fetch metadata, lyrics, and cover from API
            val apiMetadata = fetchMetadata(baseName, fetchCover = true, fetchLyrics = true)
            
            // If API fetch fails or returns null, we can't do anything for enhancement.
            if (apiMetadata == null) {
                logger.w("Failed to fetch metadata from API for: $originalName, skipping enhancement.")
                return ConversionResult.Success 
            }

            // 2. Prepare metadata and lyrics for writing
            val finalMetadata = apiMetadata.metadata 
            val lyrics = apiMetadata.lyrics
            val coverData = apiMetadata.coverData

            // 3. Write metadata, lyrics and cover back to the original file
            val result = updateAudioFileMetadata(uiFile, finalMetadata, lyrics, coverData, ext)
            
            logger.i("Metadata/lyrics/cover enhancement completed for: $originalName -> $result")
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
                    )
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


    private suspend fun fetchMetadata(keyword: String, fetchCover: Boolean, fetchLyrics: Boolean): ExtendedMusicMetadata? {
        return NeteaseApiService.getCompleteMetadata(keyword, fetchCover, fetchLyrics).getOrNull()
    }

    private fun mergeMetadata(ncmInfo: NcmInfo, apiMetadata: ExtendedMusicMetadata?, fallbackTitle: String): ExtendedMusicMetadata {
        if (apiMetadata != null) {
            return apiMetadata
        }
        // Fallback to NCM info
        val ncmMetadata = MusicMetadata(
            title = ncmInfo.title ?: fallbackTitle,
            artist = ncmInfo.artist.joinToString("/") ?: "",
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
        lyrics: String? = null,
        coverData: ByteArray? = null,
        writeAction: (java.io.OutputStream) -> Unit
    ): ConversionResult {
        val outputFile = outputDir.findFile(fileName) ?: outputDir.createFile("audio/*", fileName)
            ?: return ConversionResult.Failure(Exception("Could not create output file: $fileName"))

        return try {
            writeAudioDataToFile(outputFile, metadata, format, lyrics, coverData, writeAction)
            ConversionResult.Success
        } catch (e: Exception) {
            cleanupPartialFile(outputFile)
            ConversionResult.Failure(e)
        }
    }

    private fun writeAudioDataToFile(
        outputFile: DocumentFile,
        metadata: MusicMetadata,
        format: String,
        lyrics: String? = null,
        coverData: ByteArray? = null,
        writeAction: (java.io.OutputStream) -> Unit
    ) {
        val stream = context.contentResolver.openOutputStream(outputFile.uri)
            ?: throw Exception("Could not open output stream")

        stream.use { outputStream ->
            java.io.BufferedOutputStream(outputStream).use { bufferedOut ->
                AudioMetadataProcessor.processAudioData(
                    writer = writeAction,
                    format = format,
                    metadata = metadata,
                    lyrics = lyrics,
                    coverData = coverData,
                    outputStream = bufferedOut,
                    cacheDir = cacheDir
                )
            }
        }
    }

    private fun cleanupPartialFile(outputFile: DocumentFile) {
        try { outputFile.delete() } catch (e: Exception) {
            logger.w("Failed to cleanup partial file: ${outputFile.name}", e)
        }
    }

}