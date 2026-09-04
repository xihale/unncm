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

    @JvmStatic
    private external fun nativeDecrypt(inputFd: Int, outputFd: Int): NcmInfo
}
