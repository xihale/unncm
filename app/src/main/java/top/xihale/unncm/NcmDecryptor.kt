package top.xihale.unncm

/**
 * NCM file format decryptor implementation
 * Reference: https://git.unlock-music.dev/um/cli
 */
import android.util.Base64
import org.json.JSONObject
import top.xihale.unncm.utils.CryptoUtils
import top.xihale.unncm.utils.Logger
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays

data class NcmInfo(
    val format: String,
    val title: String?,
    val artist: List<String>,
    val album: String?,
    val cover: ByteArray?
)

class NcmDecryptor(private val inputStream: InputStream) {
    private val logger = Logger.withTag("NcmDecryptor")

    private var box: ByteArray? = null
    
    companion object {
        private const val MAGIC_HEADER = "CTENFDAM"
        private val KEY_CORE = byteArrayOf(
            0x68, 0x7a, 0x48, 0x52, 0x41, 0x6d, 0x73, 0x6f,
            0x35, 0x6b, 0x49, 0x6e, 0x62, 0x61, 0x78, 0x57
        )
        private val KEY_META = byteArrayOf(
            0x23, 0x31, 0x34, 0x6C, 0x6A, 0x6B, 0x5F, 0x21,
            0x5C, 0x5D, 0x26, 0x30, 0x55, 0x3C, 0x27, 0x28
        )
    }

    fun parseHeader(): NcmInfo? {
        logger.d("Parsing NCM stream header")
        try {
            // 1. Validate Magic Header
            val header = readNBytes(MAGIC_HEADER.length)
            if (!Arrays.equals(header, MAGIC_HEADER.toByteArray())) {
                logger.w("Invalid NCM file: magic header mismatch")
                return null
            }

            // 2. Gap 2 bytes
            readNBytes(2)

            // 3. Read Key Data
            val keyLen = readInt()
            if (keyLen < 0) {
                logger.w("Invalid key length: $keyLen")
                return null
            }

            val keyRaw = readNBytes(keyLen)
            for (i in keyRaw.indices) {
                keyRaw[i] = (keyRaw[i].toInt() xor 0x64).toByte()
            }

            val deKeyData = CryptoUtils.decryptAes128Ecb(keyRaw, KEY_CORE)
            val unpaddedKey = CryptoUtils.pkcs7UnPadding(deKeyData)
            
            if (unpaddedKey.size < 17) {
                logger.w("Decrypted key too short")
                return null
            }
            
            val boxKey = unpaddedKey.copyOfRange(17, unpaddedKey.size)
            box = CryptoUtils.buildKeyBox(boxKey)

            // 4. Read Meta Data
            val metaLen = readInt()
            var metaInfo: NcmMetaInfo? = null
            
            if (metaLen > 0) {
                val metaRaw = readNBytes(metaLen)
                metaInfo = decryptMeta(metaRaw)
            }

            // 5. Gap 5 bytes
            readNBytes(5)

            // 6. Read Cover Data
            readInt() // CRC (unused)
            val coverLen = readInt()

            var coverData: ByteArray? = null
            if (coverLen > 0) {
                coverData = readNBytes(coverLen)
            }

            val result = NcmInfo(
                format = metaInfo?.format ?: "mp3",
                title = metaInfo?.title,
                artist = metaInfo?.artist ?: emptyList(),
                album = metaInfo?.album,
                cover = coverData
            )

            logger.i("NCM header parsed: ${result.title} - ${result.artist}")
            return result

        } catch (e: Exception) {
            logger.e("Error parsing NCM header", e)
            return null
        }
    }

    fun decryptAudio(outputStream: OutputStream) {
        val currentBox = box ?: throw IllegalStateException("Header not parsed or invalid")
        val buffer = ByteArray(64 * 1024) // 64KB buffer
        var bytesRead: Int
        var currentAudioIndex = 0

        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            for (i in 0 until bytesRead) {
                val key = currentBox[(currentAudioIndex + i) and 0xFF]
                buffer[i] = (buffer[i].toInt() xor key.toInt()).toByte()
            }
            outputStream.write(buffer, 0, bytesRead)
            currentAudioIndex += bytesRead
        }
    }

    private fun readInt(): Int {
        val bytes = readNBytes(4)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun readNBytes(n: Int): ByteArray {
        val buffer = ByteArray(n)
        var offset = 0
        while (offset < n) {
            val count = inputStream.read(buffer, offset, n - offset)
            if (count == -1) {
                throw EOFException("Unexpected end of stream. Expected $n bytes, got $offset")
            }
            offset += count
        }
        return buffer
    }

    private data class NcmMetaInfo(
        val format: String,
        val title: String,
        val artist: List<String>,
        val album: String
    )

    private fun decryptMeta(metaRaw: ByteArray): NcmMetaInfo? {
        try {
            // skip "163 key(Don't modify):" (22 bytes)
            if (metaRaw.size < 22) return null
            
            val validMeta = metaRaw.copyOfRange(22, metaRaw.size)
            for (i in validMeta.indices) {
                validMeta[i] = (validMeta[i].toInt() xor 0x63).toByte()
            }

            val cipherText = Base64.decode(validMeta, Base64.DEFAULT)
            val decrypted = CryptoUtils.decryptAes128Ecb(cipherText, KEY_META)
            val unpadded = CryptoUtils.pkcs7UnPadding(decrypted)

            val sepIndex = unpadded.indexOf(':'.code.toByte())
            if (sepIndex == -1) return null

            val jsonStr = String(unpadded, sepIndex + 1, unpadded.size - (sepIndex + 1))
            val json = JSONObject(jsonStr)

            val format = json.optString("format", "mp3")
            val title = json.optString("musicName", "")
            val album = json.optString("album", "")

            val artistList = mutableListOf<String>()
            val artistsJson = json.optJSONArray("artist")
            if (artistsJson != null) {
                (0 until artistsJson.length()).forEach { i ->
                    artistsJson.optJSONArray(i)?.let { artistGroup ->
                        if (artistGroup.length() > 0) {
                            val name = artistGroup.optString(0)
                            if (name.isNotEmpty()) {
                                artistList.add(name)
                            }
                        }
                    }
                }
            }

            return NcmMetaInfo(format, title, artistList, album)
        } catch (e: Exception) {
            logger.e("Failed to decrypt metadata", e)
            return null
        }
    }
    
    private fun ByteArray.indexOf(byte: Byte): Int {
        for (i in indices) {
            if (this[i] == byte) return i
        }
        return -1
    }
}
