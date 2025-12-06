package top.xihale.unncm

data class MusicMetadata(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val coverUrl: String?,
    val duration: Long,
    val lyrics: String?,
    val coverData: ByteArray? = null
) {
    override fun toString(): String {
        return "MusicMetadata(id=$id, title='$title', artist='$artist', album='$album', coverUrl=$coverUrl, duration=$duration, lyrics=$lyrics, coverData=${if (coverData != null) "[${coverData.size} bytes]" else "null"})"
    }
}
