package top.xihale.unncm

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.RecyclerView
import top.xihale.unncm.databinding.ItemFileBinding

enum class FileStatus {
    PENDING, CONVERTING, DONE, ERROR
}

data class UiFile(
    val documentFile: DocumentFile,
    var status: FileStatus = FileStatus.PENDING
)

class FileAdapter(private var files: MutableList<UiFile>) : RecyclerView.Adapter<FileAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemFileBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = files[position]
        val fileName = item.documentFile.name ?: "Unknown"
        
        // Show name without extension
        holder.binding.tvFileName.text = fileName.substringBeforeLast('.')

        // Hide path/status as requested
        holder.binding.tvFilePath.visibility = android.view.View.GONE
    }

    override fun getItemCount() = files.size

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
