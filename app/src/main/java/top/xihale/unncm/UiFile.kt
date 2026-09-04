package top.xihale.unncm

import android.net.Uri

enum class FileStatus {
    PENDING, CONVERTING, DONE, ERROR
}

data class UiFile(
    val uri: Uri,
    val fileName: String,
    var status: FileStatus = FileStatus.PENDING
)
