package top.xihale.unncm.utils

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.collect
import top.xihale.unncm.FileStatus
import top.xihale.unncm.UiFile
import java.io.File
import java.util.concurrent.TimeUnit

object FastScanner {
    private val logger = Logger.withTag("FastScanner")

    private data class FileInfo(val id: String, val name: String, val mimeType: String)

    /**
     * Shared flow to scan children documents
     */
    private fun scanChildren(
        context: Context,
        treeUri: Uri
    ): Flow<FileInfo> = flow {
        val documentId = DocumentsContract.getDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            documentId
        )

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        val sortOrder = "${DocumentsContract.Document.COLUMN_DISPLAY_NAME} ASC"

        context.contentResolver.query(
            childrenUri,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)

            if (idCol == -1 || nameCol == -1 || mimeCol == -1) {
                logger.e("Missing essential columns in cursor projection. ID: $idCol, Name: $nameCol, MIME: $mimeCol")
                return@flow // Exit flow on critical error
            }

            while (cursor.moveToNext()) {
                val id = cursor.getString(idCol)
                val name = cursor.getString(nameCol)
                val mimeType = cursor.getString(mimeCol) ?: ""
                if (!name.isNullOrBlank()) {
                    emit(FileInfo(id, name, mimeType))
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Scan audio files returning a Flow to avoid OOM and allow cancellation
     */
    fun scanAudioFilesFlow(
        context: Context,
        treeUri: Uri
    ): Flow<UiFile> {
        return scanChildren(context, treeUri)
            .map { info ->
                if (info.name.endsWith(".ncm", ignoreCase = true) || info.mimeType.startsWith("audio/")) {
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, info.id)
                    UiFile(fileUri, info.name, FileStatus.PENDING)
                } else null
            }
            .filterNotNull()
            .catch { e -> logger.e("Error in scan flow", e) }
    }

    /**
     * List all file names (excluding directories) in the given tree URI
     */
    suspend fun listFileNames(context: Context, treeUri: Uri): Set<String> {
        val names = mutableSetOf<String>()

        try {
            scanChildren(context, treeUri)
                .map { info ->
                    if (info.mimeType != DocumentsContract.Document.MIME_TYPE_DIR) info.name else null
                }
                .filterNotNull()
                .collect { names.add(it) }
        } catch (e: Exception) {
            logger.e("Error listing file names", e)
        }
        return names
    }
}
