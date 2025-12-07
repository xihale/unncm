package top.xihale.unncm

data class MusicMetadata(
    val title: String,
    val artist: String,
    val album: String
) {
    override fun toString(): String {
        return "MusicMetadata(title='$title', artist='$artist', album='$album')"
    }
}
