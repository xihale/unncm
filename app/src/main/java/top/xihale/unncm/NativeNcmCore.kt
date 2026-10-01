package top.xihale.unncm

data class NcmInfo(
    val format: String,
    val title: String?,
    val artist: Array<String>,
    val album: String?,
    val cover: ByteArray?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NcmInfo) return false
        return format == other.format &&
            title == other.title &&
            artist.contentEquals(other.artist) &&
            album == other.album &&
            (cover?.contentEquals(other.cover) ?: (other.cover == null))
    }

    override fun hashCode(): Int {
        var result = format.hashCode()
        result = 31 * result + (title?.hashCode() ?: 0)
        result = 31 * result + artist.contentHashCode()
        result = 31 * result + (album?.hashCode() ?: 0)
        result = 31 * result + (cover?.contentHashCode() ?: 0)
        return result
    }
}

object NativeNcmCore {
    init {
        System.loadLibrary("unncm_core")
    }

    fun decrypt(inputFd: Int, outputFd: Int): NcmInfo {
        return nativeDecrypt(inputFd, outputFd)
    }

    /** 扫描探针：只读文件头判断元数据是否齐全，不读封面数据体。 */
    fun probeMetadata(inputFd: Int): MetadataProbe {
        val flags = nativeProbeMetadata(inputFd)
        return MetadataProbe(
            hasTitle = flags and FLAG_TITLE != 0,
            hasArtist = flags and FLAG_ARTIST != 0,
            hasCover = flags and FLAG_COVER != 0
        )
    }

    @JvmStatic
    private external fun nativeDecrypt(inputFd: Int, outputFd: Int): NcmInfo

    @JvmStatic
    private external fun nativeProbeMetadata(inputFd: Int): Int

    private const val FLAG_TITLE = 1
    private const val FLAG_ARTIST = 2
    private const val FLAG_COVER = 4
}

data class MetadataProbe(
    val hasTitle: Boolean,
    val hasArtist: Boolean,
    val hasCover: Boolean
) {
    /** 与扫描阶段旧 MediaMetadataRetriever 语义一致：缺任一项即待处理。 */
    val needsProcessing: Boolean
        get() = !hasTitle || !hasArtist || !hasCover
}
