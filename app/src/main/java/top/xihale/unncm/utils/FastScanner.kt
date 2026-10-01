package top.xihale.unncm.utils

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SAF 目录扫描工具：
 * 目录列表是扫描链路里最贵的操作（binder + provider 遍历），
 * 原则是每个目录在一次扫描中只列一遍，排序在内存完成。
 */
object FastScanner {
    private val logger = Logger.withTag("FastScanner")

    data class FileInfo(val id: String, val name: String, val mimeType: String) {
        val isDirectory: Boolean get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
    }

    /** 音频候选：NCM 按扩展名识别，普通音频按 MIME 识别（与扫描过滤逻辑一致）。 */
    fun isAudioCandidate(name: String, mimeType: String): Boolean {
        return name.endsWith(".ncm", ignoreCase = true) || mimeType.startsWith("audio/")
    }

    /**
     * 单次 SAF 查询列出目录全部子项。不做 provider 端排序（开销转嫁且不必要），
     * 返回前在内存按名称排序。
     */
    suspend fun listChildren(context: Context, treeUri: Uri): List<FileInfo> = withContext(Dispatchers.IO) {
        val documentId = DocumentsContract.getDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        val result = mutableListOf<FileInfo>()
        try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)

                if (idCol == -1 || nameCol == -1 || mimeCol == -1) {
                    logger.e("Missing essential columns in cursor projection. ID: $idCol, Name: $nameCol, MIME: $mimeCol")
                    return@use
                }

                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol)
                    if (name.isNullOrBlank()) continue
                    result.add(FileInfo(cursor.getString(idCol), name, cursor.getString(mimeCol) ?: ""))
                }
            }
        } catch (e: Exception) {
            logger.e("Error listing children of $treeUri", e)
        }
        result.sortBy { it.name.lowercase() }
        result
    }

    /**
     * List all file names (excluding directories) in the given tree URI
     */
    suspend fun listFileNames(context: Context, treeUri: Uri): Set<String> {
        return listChildren(context, treeUri)
            .filter { !it.isDirectory }
            .map { it.name }
            .toSet()
    }
}
