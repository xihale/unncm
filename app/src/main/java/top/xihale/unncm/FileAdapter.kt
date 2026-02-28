package top.xihale.unncm

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.AttrRes
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import top.xihale.unncm.databinding.ItemFileBinding

enum class FileStatus {
    PENDING, CONVERTING, DONE, ERROR
}

data class UiFile(
    val uri: Uri,
    val fileName: String,
    var status: FileStatus = FileStatus.PENDING
)

class FileAdapter : ListAdapter<UiFile, FileAdapter.ViewHolder>(DIFF_CALLBACK) {

    class ViewHolder(val binding: ItemFileBinding) : RecyclerView.ViewHolder(binding.root)

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).uri.toString().hashCode().toLong()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val context = holder.binding.root.context
        val card = holder.binding.root as MaterialCardView

        holder.binding.tvFileName.text = item.fileName.substringBeforeLast('.')

        val extension = item.fileName.substringAfterLast('.', "").uppercase()
        val statusText = when (item.status) {
            FileStatus.PENDING -> null
            FileStatus.CONVERTING -> context.getString(R.string.item_meta_converting)
            FileStatus.DONE -> context.getString(R.string.item_meta_done)
            FileStatus.ERROR -> context.getString(R.string.item_meta_error)
        }

        val statusColor = when (item.status) {
            FileStatus.PENDING -> color(card, com.google.android.material.R.attr.colorOnSurfaceVariant)
            FileStatus.CONVERTING -> color(card, com.google.android.material.R.attr.colorPrimary)
            FileStatus.DONE -> color(card, com.google.android.material.R.attr.colorSecondary)
            FileStatus.ERROR -> color(card, com.google.android.material.R.attr.colorError)
        }

        holder.binding.tvFileMeta.text = listOfNotNull(
            extension.takeIf { it.isNotBlank() },
            statusText
        ).joinToString(" · ")
        holder.binding.tvFileMeta.setTextColor(statusColor)
    }

    private fun color(card: MaterialCardView, @AttrRes attr: Int): Int {
        return MaterialColors.getColor(card, attr)
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<UiFile>() {
            override fun areItemsTheSame(oldItem: UiFile, newItem: UiFile): Boolean {
                return oldItem.uri == newItem.uri
            }

            override fun areContentsTheSame(oldItem: UiFile, newItem: UiFile): Boolean {
                return oldItem.fileName == newItem.fileName && oldItem.status == newItem.status
            }
        }
    }
}
