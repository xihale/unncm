package top.xihale.unncm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.images.AndroidArtwork
import org.jaudiotagger.tag.reference.PictureTypes
import top.xihale.unncm.utils.Logger
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

object AudioMetadataProcessor {
    private val logger = Logger.withTag("AudioMetadataProcessor")

    init {
        TagOptionSingleton.getInstance().isAndroid = true
    }

    private class SafeAndroidArtwork : AndroidArtwork() {
        override fun setImageFromData(): Boolean {
            // Bypass ImageIO/UnsupportedOperationException.
            // We manually populate metadata using BitmapFactory.
            return true
        }
    }

    fun processAudioData(
        writer: (OutputStream) -> Unit,
        format: String, // e.g. "mp3", "flac"
        metadata: MusicMetadata,
        lyrics: String? = null,
        coverData: ByteArray? = null,
        outputStream: OutputStream,
        cacheDir: File
    ): Result<Unit> {
        return try {
            processAudioDataInternal(writer, format, metadata, lyrics, coverData, outputStream, cacheDir)
            Result.success(Unit)
        } catch (e: Exception) {
            logger.e("Error processing tags", e)
            Result.failure(e)
        }
    }

    /**
     * Async version of processAudioData for better IO performance
     */
    suspend fun processAudioDataAsync(
        writer: (OutputStream) -> Unit,
        format: String, // e.g. "mp3", "flac"
        metadata: MusicMetadata,
        lyrics: String? = null,
        coverData: ByteArray? = null,
        outputStream: OutputStream,
        cacheDir: File
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            processAudioDataInternal(writer, format, metadata, lyrics, coverData, outputStream, cacheDir)
            Result.success(Unit)
        } catch (e: Exception) {
            logger.e("Error processing tags", e)
            Result.failure(e)
        }
    }

    private fun processAudioDataInternal(
        writer: (OutputStream) -> Unit,
        format: String,
        metadata: MusicMetadata,
        lyrics: String? = null,
        coverData: ByteArray? = null,
        outputStream: OutputStream,
        cacheDir: File
    ) {
        var tempFile: File? = null
        try {
            // 1. Create a temporary file
            tempFile = File(cacheDir, "tag_temp_${UUID.randomUUID()}.$format")
            
            // 2. Write Decrypted Data to Temp File using the provider writer
            // Use BufferedOutputStream for performance
            tempFile.outputStream().buffered(64 * 1024).use { fileOut ->
                writer(fileOut)
            }
            
            // 3. Process Tags on Temp File
            val audioFile = AudioFileIO.read(tempFile)
            var tag = audioFile.tag
            if (tag == null) {
                tag = audioFile.createDefaultTag()
                audioFile.tag = tag
            }
            
            try { tag.setField(FieldKey.TITLE, metadata.title) } catch (e: Exception) {}
            try { tag.setField(FieldKey.ARTIST, metadata.artist) } catch (e: Exception) {}
            try { tag.setField(FieldKey.ALBUM, metadata.album) } catch (e: Exception) {}
            
            if (!lyrics.isNullOrEmpty()) {
                try {
                    tag.setField(FieldKey.LYRICS, lyrics)
                } catch (e: Exception) {
                     logger.w("Could not set lyrics: ${e.message}")
                }
            }

            if (coverData != null && coverData.isNotEmpty()) {
                try {
                    // Decode image bounds and mime type using Android API
                    val options = android.graphics.BitmapFactory.Options()
                    options.inJustDecodeBounds = true
                    android.graphics.BitmapFactory.decodeByteArray(coverData, 0, coverData.size, options)

                    // Use custom SafeAndroidArtwork to avoid ImageIO dependency and UnsupportedOperationException
                    val artwork = SafeAndroidArtwork()
                    artwork.binaryData = coverData
                    artwork.mimeType = options.outMimeType ?: "image/jpeg"
                    artwork.width = options.outWidth
                    artwork.height = options.outHeight
                    artwork.pictureType = PictureTypes.DEFAULT_ID // Front Cover
                    artwork.isLinked = false
                    
                    tag.deleteArtworkField()
                    tag.setField(artwork)
                } catch (e: Throwable) {
                    logger.e("Failed to set artwork", e)
                }
            }
            
            audioFile.commit()
            
            // 4. Copy Modified Temp File to Output Stream
            // Use BufferedInputStream and large buffer for copy
            tempFile.inputStream().buffered(64 * 1024).use { fileIn ->
                fileIn.copyTo(outputStream, bufferSize = 64 * 1024)
            }
        } catch (e: Exception) {
            throw e
        } finally {
            // 5. Clean up
            try { tempFile?.delete() } catch (e: Exception) {}
        }
    }

    /**
     * Result of metadata analysis
     */
    data class MetadataAnalysisResult(
        val hasCompleteMetadata: Boolean,
        val hasLyrics: Boolean,
        val hasCover: Boolean,
        val existingTags: MusicMetadata
    )

    /**
     * Analyzes metadata from an input stream by streaming it to a temporary file once.
     */
    fun analyzeMetadata(inputStream: InputStream, fileName: String, cacheDir: File): MetadataAnalysisResult {
        val tempFile = File(cacheDir, "analysis_${System.currentTimeMillis()}_$fileName")
        try {
            // Stream to temp file
            BufferedInputStream(inputStream).use { input ->
                tempFile.outputStream().buffered().use { output ->
                    input.copyTo(output)
                }
            }

            // Perform checks on the temp file
            val hasComplete = checkCompleteMetadata(tempFile)
            val hasLyrics = checkLyrics(tempFile)
            val hasCover = checkCoverArt(tempFile)

            // Read existing tags
            val tags = try {
                val audio = AudioFileIO.read(tempFile)
                val tag = audio.tagOrCreateAndSetDefault
                MusicMetadata(
                    title = tag.getFirst(FieldKey.TITLE) ?: "",
                    artist = tag.getFirst(FieldKey.ARTIST) ?: "",
                    album = tag.getFirst(FieldKey.ALBUM) ?: ""
                )
            } catch (e: Exception) {
                MusicMetadata("", "", "")
            }

            return MetadataAnalysisResult(hasComplete, hasLyrics, hasCover, tags)

        } catch (e: Exception) {
            logger.e("Error analyzing metadata for $fileName", e)
            return MetadataAnalysisResult(false, false, false, MusicMetadata("", "", ""))
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Async version of analyzeMetadata for better performance
     */
    suspend fun analyzeMetadataAsync(inputStream: InputStream, fileName: String, cacheDir: File): MetadataAnalysisResult = withContext(Dispatchers.IO) {
        val tempFile = File(cacheDir, "analysis_${System.currentTimeMillis()}_$fileName")
        try {
            // Stream to temp file with optimized buffer for large files
            BufferedInputStream(inputStream, 256 * 1024).use { input ->
                tempFile.outputStream().buffered(256 * 1024).use { output ->
                    // Use progress callback for large files
                    val totalCopied = input.copyToWithProgress(output, bufferSize = 256 * 1024) { bytesCopied ->
                        if (bytesCopied % (10 * 1024 * 1024) == 0L) { // Log every 10MB
                            logger.d("Copied ${bytesCopied / 1024 / 1024}MB for $fileName")
                        }
                    }
                    logger.d("Total copied: ${totalCopied / 1024 / 1024}MB for $fileName")
                }
            }

            // Perform checks on the temp file
            val hasComplete = checkCompleteMetadata(tempFile)
            val hasLyrics = checkLyrics(tempFile)
            val hasCover = checkCoverArt(tempFile)

            // Read existing tags
            val tags = try {
                val audio = AudioFileIO.read(tempFile)
                val tag = audio.tagOrCreateAndSetDefault
                MusicMetadata(
                    title = tag.getFirst(FieldKey.TITLE) ?: "",
                    artist = tag.getFirst(FieldKey.ARTIST) ?: "",
                    album = tag.getFirst(FieldKey.ALBUM) ?: ""
                )
            } catch (e: Exception) {
                MusicMetadata("", "", "")
            }

            MetadataAnalysisResult(hasComplete, hasLyrics, hasCover, tags)

        } catch (e: Exception) {
            logger.e("Error analyzing metadata for $fileName", e)
            MetadataAnalysisResult(false, false, false, MusicMetadata("", "", ""))
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Memory-efficient metadata analysis for large files
     * Only analyzes the first few KB of the file for metadata
     */
    suspend fun analyzeMetadataLightweight(inputStream: InputStream, fileName: String, cacheDir: File): MetadataAnalysisResult = withContext(Dispatchers.IO) {
        try {
            // Only read first 1MB for metadata analysis (most metadata is at the beginning)
            val headerBuffer = ByteArray(1024 * 1024) // 1MB buffer
            val bytesRead = inputStream.use { input ->
                input.read(headerBuffer)
            }

            if (bytesRead <= 0) {
                logger.w("Empty file: $fileName")
                return@withContext MetadataAnalysisResult(false, false, false, MusicMetadata("", "", ""))
            }

            // Create temp file with only the header for analysis
            val tempFile = File(cacheDir, "lightweight_${System.currentTimeMillis()}_$fileName")
            try {
                tempFile.writeBytes(headerBuffer.copyOf(bytesRead))

                // Perform basic checks
                val hasComplete = checkCompleteMetadataLightweight(tempFile)

                // Check for lyrics in the header (even though it's lightweight)
                val hasLyrics = checkLyricsLightweight(tempFile)

                // For cover art, we assume false in lightweight mode since cover data is usually at the end
                // But we should be more careful about this assumption
                val hasCover = false

                // Read basic tags
                val tags = try {
                    val audio = AudioFileIO.read(tempFile)
                    val tag = audio.tagOrCreateAndSetDefault
                    MusicMetadata(
                        title = tag.getFirst(FieldKey.TITLE) ?: "",
                        artist = tag.getFirst(FieldKey.ARTIST) ?: "",
                        album = tag.getFirst(FieldKey.ALBUM) ?: ""
                    )
                } catch (e: Exception) {
                    MusicMetadata("", "", "")
                }

                MetadataAnalysisResult(hasComplete, hasLyrics, hasCover, tags)

            } finally {
                tempFile.delete()
            }

        } catch (e: Exception) {
            logger.e("Error in lightweight metadata analysis for $fileName", e)
            MetadataAnalysisResult(false, false, false, MusicMetadata("", "", ""))
        }
    }

    /**
     * Check if audio file already has complete metadata (title, artist, album)
     */
    private fun checkCompleteMetadata(audioFile: File): Boolean {
        return try {
            val audio = AudioFileIO.read(audioFile)
            val tag = audio.tag ?: return false

            val title = tag.getFirst(FieldKey.TITLE)
            val artist = tag.getFirst(FieldKey.ARTIST)
            val album = tag.getFirst(FieldKey.ALBUM)

            !title.isNullOrBlank() && !artist.isNullOrBlank() && !album.isNullOrBlank()
        } catch (e: Exception) {
            logger.w("Failed to read metadata from ${audioFile.name}", e)
            false
        }
    }

    /**
     * Check if audio file has cover art
     */
    private fun checkCoverArt(audioFile: File): Boolean {
        return try {
            val audio = AudioFileIO.read(audioFile)
            val tag = audio.tag ?: return false
            tag.artworkList.isNotEmpty()
        } catch (e: Exception) {
            logger.w("Failed to read cover art from ${audioFile.name}", e)
            false
        }
    }

    /**
     * Check if audio file has lyrics
     */
    private fun checkLyrics(audioFile: File): Boolean {
        return try {
            val audio = AudioFileIO.read(audioFile)
            val tag = audio.tag ?: return false
            val lyrics = tag.getFirst(FieldKey.LYRICS)
            !lyrics.isNullOrBlank()
        } catch (e: Exception) {
            logger.w("Failed to read lyrics from ${audioFile.name}", e)
            false
        }
    }


    /**
     * Lightweight version of complete metadata check for large files
     */
    private fun checkCompleteMetadataLightweight(audioFile: File): Boolean {
        return try {
            val audio = AudioFileIO.read(audioFile)
            val tag = audio.tag ?: return false

            val title = tag.getFirst(FieldKey.TITLE)
            val artist = tag.getFirst(FieldKey.ARTIST)
            val album = tag.getFirst(FieldKey.ALBUM)

            !title.isNullOrBlank() && !artist.isNullOrBlank() && !album.isNullOrBlank()
        } catch (e: Exception) {
            logger.w("Failed to read metadata from ${audioFile.name}", e)
            false
        }
    }

    /**
     * Lightweight version of lyrics check for large files
     */
    private fun checkLyricsLightweight(audioFile: File): Boolean {
        return try {
            val audio = AudioFileIO.read(audioFile)
            val tag = audio.tag ?: return false

            val lyrics = tag.getFirst(FieldKey.LYRICS)
            !lyrics.isNullOrBlank() && lyrics.trim().length > 10 // Ensure meaningful lyrics content
        } catch (e: Exception) {
            logger.w("Failed to check lyrics in ${audioFile.name}", e)
            false
        }
    }
}

/**
 * Extension function to copy input stream with progress callback
 */
private fun InputStream.copyToWithProgress(
    out: OutputStream,
    bufferSize: Int = 8192,
    progressCallback: (Long) -> Unit = {}
): Long {
    var bytesCopied: Long = 0
    val buffer = ByteArray(bufferSize)
    var bytes = read(buffer)
    while (bytes >= 0) {
        out.write(buffer, 0, bytes)
        bytesCopied += bytes
        progressCallback(bytesCopied)
        bytes = read(buffer)
    }
    return bytesCopied
}