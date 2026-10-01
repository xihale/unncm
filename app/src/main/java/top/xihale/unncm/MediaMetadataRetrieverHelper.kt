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
            extractMetadataNeeds(retriever)
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
        // 本地文件路径直读，绕过 ContentResolver/SAF 中转；扫描大量文件时明显更快
        if (fileUri.scheme == "file") {
            val path = fileUri.path
            if (!path.isNullOrBlank()) {
                return analyzeMetadataNeedsFromPath(path).needsProcessing
            }
        }
        return analyzeMetadataNeedsFromUri(context, fileUri).needsProcessing
    }

    /**
     * 扫描阶段的元数据探针：mp3/flac 走 Rust 头解析（不读封面数据体，单文件毫秒级），
     * 其余格式回退到 MediaMetadataRetriever 保持旧行为。
     */
    suspend fun probeNeedsProcessing(context: Context, fileUri: Uri, fileName: String): Boolean =
        withContext(Dispatchers.IO) {
            val extension = fileName.substringAfterLast('.', "").lowercase()
            if (extension != "mp3" && extension != "flac") {
                // 非 Rust 探针覆盖的格式回退框架解析，较慢；记日志便于定位扫描慢的来源
                logger.i("probe: MMR fallback for .$extension file: $fileName")
                return@withContext analyzeMetadataFromUri(context, fileUri)
            }

            val descriptor = try {
                context.contentResolver.openFileDescriptor(fileUri, "r")
            } catch (e: Exception) {
                logger.w("Failed to open descriptor for probe: $fileUri", e)
                null
            } ?: return@withContext true

            descriptor.use { pfd ->
                try {
                    NativeNcmCore.probeMetadata(pfd.fd).needsProcessing
                } catch (e: Exception) {
                    logger.w("Rust probe failed for: $fileUri", e)
                    true
                }
            }
        }

    private fun analyzeMetadataNeedsFromPath(path: String): MetadataNeeds {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            extractMetadataNeeds(retriever)
        } catch (e: Exception) {
            logger.e("Failed to analyze metadata from path: $path", e)
            MetadataNeeds(missingTitle = true, missingArtist = true, missingCover = true)
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
                // Ignore release errors
            }
        }
    }

    private fun extractMetadataNeeds(retriever: MediaMetadataRetriever): MetadataNeeds {
        val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
        val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
        val hasCover = retriever.embeddedPicture != null

        return MetadataNeeds(
            missingTitle = title.isNullOrBlank(),
            missingArtist = artist.isNullOrBlank(),
            missingCover = !hasCover
        )
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
