package top.xihale.unncm

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.RecyclerView
import top.xihale.unncm.databinding.ItemFileBinding

enum class FileStatus {
    PENDING, CONVERTING, DONE, ERROR
}

data class UiFile(
    val uri: Uri,
    val fileName: String,
    var status: FileStatus = FileStatus.PENDING
) {
    // Helper constructor for compatibility during refactor if needed, 
    // but we should switch to using Uri primarily.
    constructor(documentFile: DocumentFile, fileName: String, status: FileStatus = FileStatus.PENDING) 
        : this(documentFile.uri, fileName, status)
}

class FileAdapter(private var files: MutableList<UiFile>) : RecyclerView.Adapter<FileAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemFileBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = files[position]
        
        // Show name without extension
        holder.binding.tvFileName.text = item.fileName.substringBeforeLast('.')

        // Hide path/status as requested
        holder.binding.tvFilePath.visibility = android.view.View.GONE
    }

    override fun getItemCount() = files.size

    @SuppressLint("NotifyDataSetChanged")
    fun updateList(newFiles: List<UiFile>) {
        files = newFiles.toMutableList()
        notifyDataSetChanged()
    }

    fun removeItem(item: UiFile) {
        val index = files.indexOf(item)
        if (index != -1) {
            files.removeAt(index)
            notifyItemRemoved(index)
        }
    }
}