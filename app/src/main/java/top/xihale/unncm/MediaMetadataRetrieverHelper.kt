package top.xihale.unncm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.xihale.unncm.utils.Logger

/**
 * Helper class for efficient metadata detection using MediaMetadataRetriever
 */
object MediaMetadataRetrieverHelper {
    private val logger = Logger.withTag("MediaMetadataRetriever")

    data class MetadataNeeds(
        val missingTitle: Boolean,
        val missingArtist: Boolean,
        val missingCover: Boolean
    ) {
        val needsProcessing: Boolean
            get() = missingTitle || missingArtist || missingCover
    }

    /**
     * Returns which fields are missing from a file URI.
     */
    fun analyzeMetadataNeedsFromUri(context: Context, fileUri: Uri): MetadataNeeds {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, fileUri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val hasCover = retriever.embeddedPicture != null

            MetadataNeeds(
                missingTitle = title.isNullOrBlank(),
                missingArtist = artist.isNullOrBlank(),
                missingCover = !hasCover
            )
        } catch (e: Exception) {
            logger.e("Failed to analyze metadata from URI: $fileUri", e)
            MetadataNeeds(missingTitle = true, missingArtist = true, missingCover = true)
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
                // Ignore release errors
            }
        }
    }

    /**
     * Backward-compatible shortcut.
     * Returns true if processing is needed.
     */
    fun analyzeMetadataFromUri(context: Context, fileUri: Uri): Boolean {
        return analyzeMetadataNeedsFromUri(context, fileUri).needsProcessing
    }

    suspend fun extractEmbeddedThumbnail(
        context: Context,
        fileUri: Uri,
        desiredSizePx: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, fileUri)
            val picture = retriever.embeddedPicture ?: return@withContext null
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bitmap = BitmapFactory.decodeByteArray(picture, 0, picture.size, options) ?: return@withContext null
            Bitmap.createScaledBitmap(bitmap, desiredSizePx, desiredSizePx, true)
        } catch (e: Exception) {
            logger.w("Failed to extract thumbnail from URI: $fileUri", e)
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                logger.w("Failed to release metadata retriever while loading thumbnail", e)
            }
        }
    }
}
