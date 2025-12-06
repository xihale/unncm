package top.xihale.unncm.utils

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import top.xihale.unncm.UiFile
import top.xihale.unncm.FileStatus
import top.xihale.unncm.AudioMetadataProcessor
import java.util.concurrent.TimeUnit
import java.io.File

object FastScanner {
    private val logger = Logger.withTag("FastScanner")

    fun scanAudioFiles(context: Context, treeUri: Uri, checkMetadata: Boolean = true): List<UiFile> {
        var results = mutableListOf<UiFile>()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getDocumentId(treeUri)
        )

        // Query audio files and NCM files
        val selection = "${DocumentsContract.Document.COLUMN_MIME_TYPE} LIKE ? OR ${DocumentsContract.Document.COLUMN_DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("audio/*", "%.ncm")

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        try {
            context.contentResolver.query(
                childrenUri,
                projection,
                selection,
                selectionArgs,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME + " ASC" // Sort to improve cache performance
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)

                // Pre-allocate capacity for better performance
                if (cursor.count > 0) {
                    results = ArrayList(cursor.count)
                }

                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol) ?: continue
                    val mimeType = cursor.getString(mimeCol) ?: ""

                    // Check if it's an NCM file or other audio file
                    val isNcmFile = name.endsWith(".ncm", ignoreCase = true)
                    val isAudioFile = mimeType.startsWith("audio/")

                    if (isNcmFile || isAudioFile) {
                        val docId = cursor.getString(idCol)
                        val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        
                        if (isNcmFile) {
                            results.add(UiFile(fileUri, name, FileStatus.PENDING))
                        } else {
                            if (!checkMetadata) {
                                // If we don't need to check metadata (e.g. scanning output dir),
                                // just add the file.
                                results.add(UiFile(fileUri, name, FileStatus.PENDING))
                            } else {
                                // For metadata check, we need to read the file.
                                val docFile = DocumentFile.fromSingleUri(context, fileUri)
                                if (docFile != null) {
                                    val fileStatus = checkAudioFileMetadata(context, docFile, name)
                                    if (fileStatus.needsProcessing) {
                                        results.add(UiFile(fileUri, name, FileStatus.PENDING))
                                        logger.d("Audio file needs processing (${fileStatus.missingFields.joinToString(", ")}): $name")
                                    } else {
                                        logger.d("Audio file already has complete metadata and lyrics: $name")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.e("Error fast scanning audio files", e)
        }
        return results
    }

    /**
     * Audio file check result
     */
    data class AudioFileCheckResult(
        val needsProcessing: Boolean,
        val missingFields: List<String>
    )

    /**
     * Check if audio file is missing metadata or lyrics
     */
    fun checkAudioFileMetadata(context: Context, docFile: DocumentFile, fileName: String): AudioFileCheckResult {
        return try {
            val cacheDir = File(context.cacheDir, "metadata_check")
            cacheDir.mkdirs()
            
            val missingFields = mutableListOf<String>()

            context.contentResolver.openInputStream(docFile.uri)?.use { input ->
                val analysis = AudioMetadataProcessor.analyzeMetadata(input, fileName, cacheDir)
                
                if (!analysis.hasCompleteMetadata) {
                    missingFields.add("metadata")
                }
                if (!analysis.hasLyrics) {
                    missingFields.add("lyrics")
                }
            }

            AudioFileCheckResult(
                needsProcessing = missingFields.isNotEmpty(),
                missingFields = missingFields
            )
        } catch (e: Exception) {
            logger.w("Failed to check metadata for $fileName", e)
            // If detection fails, assume processing is needed and add to processing list
            AudioFileCheckResult(true, listOf("detection_failed"))
        }
    }

    // Removed unused scanNcmFiles


    fun listFileNames(context: Context, treeUri: Uri): Set<String> {
        // Force refresh cache
        forceRefreshCache(context, treeUri)

        var names = mutableSetOf<String>()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getDocumentId(treeUri)
        )

        // Optimization: Add MIME_TYPE filter to only query files
        val selection = "${DocumentsContract.Document.COLUMN_MIME_TYPE} != ?"
        val selectionArgs = arrayOf(DocumentsContract.Document.MIME_TYPE_DIR)

        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)

        try {
            context.contentResolver.query(
                childrenUri,
                projection,
                selection,
                selectionArgs,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME + " ASC" // Sort to improve cache performance
            )?.use { cursor ->
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)

                // Pre-allocate capacity
                if (cursor.count > 0) {
                    // Create new LinkedHashSet to replace existing names
                    val newNames = LinkedHashSet<String>(cursor.count) // Use LinkedHashSet to maintain insertion order
                    names = newNames
                }

                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol)
                    if (!name.isNullOrBlank()) {
                        names.add(name)
                    }
                }
            }
        } catch (e: Exception) {
            logger.e("Error listing file names", e)
        }
        return names
    }

    /**
     * Force refresh document cache
     */
    private fun forceRefreshCache(context: Context, treeUri: Uri) {
        try {
            // 1. Notify system of data changes
            context.contentResolver.notifyChange(treeUri, null)

            // 2. Force rescan directory (only root directory, not subdirectories)
            DocumentFile.fromTreeUri(context, treeUri)?.let { docFile ->
                // Trigger directory reload
                docFile.listFiles()
            }

            // 3. Brief delay to let system handle cache updates
            TimeUnit.MILLISECONDS.sleep(50)

        } catch (e: Exception) {
            logger.w("Failed to force refresh cache", e)
        }
    }
}
