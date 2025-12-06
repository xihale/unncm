package top.xihale.unncm

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
        outputStream: OutputStream,
        cacheDir: File
    ): Result<Unit> {
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
            
            if (!metadata.lyrics.isNullOrEmpty()) {
                try {
                    tag.setField(FieldKey.LYRICS, metadata.lyrics)
                } catch (e: Exception) {
                     logger.w("Could not set lyrics: ${e.message}")
                }
            }
            
            if (metadata.coverData != null && metadata.coverData.isNotEmpty()) {
                try {
                    // Decode image bounds and mime type using Android API
                    val options = android.graphics.BitmapFactory.Options()
                    options.inJustDecodeBounds = true
                    android.graphics.BitmapFactory.decodeByteArray(metadata.coverData, 0, metadata.coverData.size, options)
                    
                    // Use custom SafeAndroidArtwork to avoid ImageIO dependency and UnsupportedOperationException
                    val artwork = SafeAndroidArtwork()
                    artwork.binaryData = metadata.coverData
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
            
            return Result.success(Unit)
        } catch (e: Exception) {
            logger.e("Error processing tags", e)
            return Result.failure(e)
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
                    id = 0,
                    title = tag.getFirst(FieldKey.TITLE) ?: "",
                    artist = tag.getFirst(FieldKey.ARTIST) ?: "",
                    album = tag.getFirst(FieldKey.ALBUM) ?: "",
                    coverUrl = null,
                    duration = 0,
                    lyrics = tag.getFirst(FieldKey.LYRICS),
                    coverData = null
                )
            } catch (e: Exception) {
                MusicMetadata(0, "", "", "", null, 0, null, null)
            }

            return MetadataAnalysisResult(hasComplete, hasLyrics, hasCover, tags)

        } catch (e: Exception) {
            logger.e("Error analyzing metadata for $fileName", e)
            return MetadataAnalysisResult(false, false, false, MusicMetadata(0, "", "", "", null, 0, null, null))
        } finally {
            tempFile.delete()
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


    // Removed redundant InputStream methods to encourage efficient temp file usage via analyzeMetadata

}