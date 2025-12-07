package top.xihale.unncm

import android.content.Context
import android.media.MediaMetadataRetriever
import top.xihale.unncm.utils.Logger

/**
 * Helper class for efficient metadata detection using MediaMetadataRetriever
 */
object MediaMetadataRetrieverHelper {
    private val logger = Logger.withTag("MediaMetadataRetriever")
    
    /**
     * Check if metadata (title/artist) is missing from a file URI.
     * Returns true if processing is needed (metadata missing).
     */
    fun analyzeMetadataFromUri(context: Context, fileUri: android.net.Uri): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, fileUri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val hasCover = retriever.embeddedPicture != null
            
            // Need processing if title or artist is missing/empty, or cover is missing
            title.isNullOrBlank() || artist.isNullOrBlank() || !hasCover
        } catch (e: Exception) {
            logger.e("Failed to analyze metadata from URI: $fileUri", e)
            true // Assume needs processing on error
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // Ignore release errors
            }
        }
    }
}