package top.xihale.unncm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.images.AndroidArtwork
import org.jaudiotagger.tag.reference.PictureTypes
import top.xihale.unncm.utils.Logger
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

    /** Adds tags to an already materialized audio file and streams it out. */
    fun processExistingAudioFile(
        audioFile: File,
        metadata: MusicMetadata,
        lyrics: String? = null,
        coverData: ByteArray? = null,
        outputStream: OutputStream
    ): Result<Unit> {
        return try {
            tagAndCopyAudioFile(audioFile, metadata, lyrics, coverData, outputStream)
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
            
            tagAndCopyAudioFile(tempFile, metadata, lyrics, coverData, outputStream)
        } finally {
            // 3. Clean up
            try { tempFile?.delete() } catch (e: Exception) {}
        }
    }

    private fun tagAndCopyAudioFile(
        sourceFile: File,
        metadata: MusicMetadata,
        lyrics: String?,
        coverData: ByteArray?,
        outputStream: OutputStream
    ) {
        val audioFile = AudioFileIO.read(sourceFile)
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

        // Copy the tagged file to its SAF destination.
        sourceFile.inputStream().buffered(64 * 1024).use { fileIn ->
            fileIn.copyTo(outputStream, bufferSize = 64 * 1024)
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
     * 元数据集中在文件头部（ID3v2 / FLAC metadata block），读前 1MB 即可覆盖绝大多数情况。
     */
    private const val HEADER_READ_LIMIT = 1024 * 1024

    /**
     * Lightweight metadata analysis: 只读文件头部，避免为读标签而拷贝整个音频文件。
     * 头部截断导致解析失败时返回空结果，上层会按"需要补全"兜底，方向安全。
     */
    suspend fun analyzeMetadataLightweight(inputStream: InputStream, fileName: String, cacheDir: File): MetadataAnalysisResult = withContext(Dispatchers.IO) {
        try {
            // InputStream.read 单次调用不保证填满缓冲区，必须循环读满
            val headerBuffer = ByteArray(HEADER_READ_LIMIT)
            var offset = 0
            inputStream.use { input ->
                while (offset < headerBuffer.size) {
                    val read = input.read(headerBuffer, offset, headerBuffer.size - offset)
                    if (read < 0) break
                    offset += read
                }
            }

            if (offset <= 0) {
                logger.w("Empty file: $fileName")
                return@withContext emptyMetadataAnalysisResult()
            }

            val tempFile = File(cacheDir, "lightweight_${System.currentTimeMillis()}_$fileName")
            try {
                tempFile.writeBytes(headerBuffer.copyOf(offset))
                analyzeTagFromFile(audioFile = tempFile, skipCoverCheck = false)
            } finally {
                tempFile.delete()
            }
        } catch (e: Exception) {
            logger.e("Error in lightweight metadata analysis for $fileName", e)
            emptyMetadataAnalysisResult()
        }
    }

    private fun analyzeTagFromFile(
        audioFile: File,
        lyricsMinLength: Int = 1,
        skipCoverCheck: Boolean = false
    ): MetadataAnalysisResult {
        return try {
            val audio = AudioFileIO.read(audioFile)
            val tag = audio.tag ?: return emptyMetadataAnalysisResult()

            val title = tag.getFirst(FieldKey.TITLE) ?: ""
            val artist = tag.getFirst(FieldKey.ARTIST) ?: ""
            val album = tag.getFirst(FieldKey.ALBUM) ?: ""
            val lyrics = tag.getFirst(FieldKey.LYRICS) ?: ""

            val hasComplete = title.isNotBlank() && artist.isNotBlank() && album.isNotBlank()
            val hasLyrics = if (lyricsMinLength <= 1) {
                lyrics.isNotBlank()
            } else {
                lyrics.trim().length > lyricsMinLength
            }
            val hasCover = if (skipCoverCheck) false else tag.artworkList.isNotEmpty()

            MetadataAnalysisResult(
                hasCompleteMetadata = hasComplete,
                hasLyrics = hasLyrics,
                hasCover = hasCover,
                existingTags = MusicMetadata(title, artist, album)
            )
        } catch (e: Exception) {
            logger.w("Failed to analyze metadata from ${audioFile.name}", e)
            emptyMetadataAnalysisResult()
        }
    }

    private fun emptyMetadataAnalysisResult(): MetadataAnalysisResult {
        return MetadataAnalysisResult(false, false, false, MusicMetadata("", "", ""))
    }
}

