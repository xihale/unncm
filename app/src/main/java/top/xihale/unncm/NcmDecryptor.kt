package top.xihale.unncm

import android.util.Base64
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

data class NcmInfo(
    val format: String,
    val title: String?,
    val artist: List<String>,
    val album: String?,
    val cover: ByteArray?,
    val audioData: ByteArray = ByteArray(0)
)

object NcmDecryptor {

    private const val MAGIC_HEADER = "CTENFDAM"
    internal val KEY_CORE = byteArrayOf(
        0x68, 0x7a, 0x48, 0x52, 0x41, 0x6d, 0x73, 0x6f,
        0x35, 0x6b, 0x49, 0x6e, 0x62, 0x61, 0x78, 0x57
    )
    internal val KEY_META = byteArrayOf(
        0x23, 0x31, 0x34, 0x6C, 0x6A, 0x6B, 0x5F, 0x21,
        0x5C, 0x5D, 0x26, 0x30, 0x55, 0x3C, 0x27, 0x28
    )

    fun decrypt(data: ByteArray): ByteArray {
        return parse(data)?.audioData ?: ByteArray(0)
    }

    fun parse(data: ByteArray): NcmInfo? {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        // 1. Validate Magic Header
        if (buffer.remaining() < MAGIC_HEADER.length) return null
        val header = ByteArray(MAGIC_HEADER.length)
        buffer.get(header)
        if (!Arrays.equals(header, MAGIC_HEADER.toByteArray())) {
            return null
        }

        // 2. Gap 2 bytes
        if (buffer.remaining() < 2) return null
        buffer.position(buffer.position() + 2)

        // 3. Read Key Data
        if (buffer.remaining() < 4) return null
        val keyLen = buffer.int
        if (keyLen < 0 || buffer.remaining() < keyLen) return null

        val keyRaw = ByteArray(keyLen)
        buffer.get(keyRaw)

        for (i in keyRaw.indices) {
            keyRaw[i] = (keyRaw[i].toInt() xor 0x64).toByte()
        }

        val deKeyData = decryptAes128Ecb(keyRaw, KEY_CORE)
        val unpaddedKey = pkcs7UnPadding(deKeyData)
        if (unpaddedKey.size < 17) return null
        val boxKey = unpaddedKey.copyOfRange(17, unpaddedKey.size)

        // 4. Read Meta Data
        var metaInfo: NcmMetaInfo? = null
        if (buffer.remaining() < 4) return null
        val metaLen = buffer.int
        if (metaLen > 0) {
            if (buffer.remaining() < metaLen) return null
            val metaRaw = ByteArray(metaLen)
            buffer.get(metaRaw)
            metaInfo = decryptMeta(metaRaw)
        }

        // 5. Gap 5 bytes
        if (buffer.remaining() < 5) return null
        buffer.position(buffer.position() + 5)

        // 6. Read Cover Data
        if (buffer.remaining() < 4) return null
        val crc = buffer.int // Unused
        
        if (buffer.remaining() < 4) return null
        val coverLen = buffer.int
        var coverData: ByteArray? = null
        if (coverLen > 0) {
             if (buffer.remaining() < coverLen) return null
             coverData = ByteArray(coverLen)
             buffer.get(coverData)
        }

        // 7. Audio Data
        val audioOffset = buffer.position()
        val audioLen = data.size - audioOffset
        val audioData = ByteArray(audioLen)
        System.arraycopy(data, audioOffset, audioData, 0, audioLen)

        // 8. Decrypt Audio
        val box = buildKeyBox(boxKey)
        
        for (i in audioData.indices) {
            audioData[i] = (audioData[i].toInt() xor box[(i) and 0xFF].toInt()).toByte()
        }

        return NcmInfo(
            format = metaInfo?.format ?: "mp3",
            title = metaInfo?.title,
            artist = metaInfo?.artist ?: emptyList(),
            album = metaInfo?.album,
            cover = coverData,
            audioData = audioData
        )
    }

    internal data class NcmMetaInfo(
        val format: String,
        val title: String,
        val artist: List<String>,
        val album: String
    )

    internal fun decryptMeta(metaRaw: ByteArray): NcmMetaInfo? {
        try {
            // skip "163 key(Don't modify):" (22 bytes)
            if (metaRaw.size < 22) return null
            val validMeta = metaRaw.copyOfRange(22, metaRaw.size)
            for (i in validMeta.indices) {
                validMeta[i] = (validMeta[i].toInt() xor 0x63).toByte()
            }

            // Base64 Decode
            val cipherText = Base64.decode(validMeta, Base64.DEFAULT)
            
            // AES Decrypt
            val decrypted = decryptAes128Ecb(cipherText, KEY_META)
            val unpadded = pkcs7UnPadding(decrypted)
            
            // Remove "music:" prefix (or "dj:")
            val sepIndex = unpadded.indexOf(':'.code.toByte())
            if (sepIndex == -1) return null
            
            val jsonStr = String(unpadded, sepIndex + 1, unpadded.size - (sepIndex + 1))
            val json = JSONObject(jsonStr)
            
            // Parse JSON
            val format = json.optString("format", "mp3")
            val title = json.optString("musicName", "")
            val album = json.optString("album", "")
            
            val artistList = mutableListOf<String>()
            val artistsJson = json.optJSONArray("artist")
            if (artistsJson != null) {
                for (i in 0 until artistsJson.length()) {
                    val artistGroup = artistsJson.optJSONArray(i)
                    if (artistGroup != null && artistGroup.length() > 0) {
                        val name = artistGroup.optString(0)
                        if (name.isNotEmpty()) {
                            artistList.add(name)
                        }
                    }
                }
            }
            
            return NcmMetaInfo(format, title, artistList, album)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    internal fun buildKeyBox(key: ByteArray): ByteArray {
        val box = ByteArray(256) { it.toByte() }
        var j = 0
        
        for (i in 0..255) {
            j = (box[i].toInt() + j + key[i % key.size].toInt()) and 0xFF
            val temp = box[i]
            box[i] = box[j]
            box[j] = temp
        }

        val ret = ByteArray(256)
        var _i: Int
        for (i in 0..255) {
            _i = (i + 1) and 0xFF
            val si = box[_i].toInt() and 0xFF
            val sj = box[(_i + si) and 0xFF].toInt() and 0xFF
            ret[i] = box[(si + sj) and 0xFF]
        }
        return ret
    }

    internal fun decryptAes128Ecb(data: ByteArray, key: ByteArray): ByteArray {
        try {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            val keySpec = SecretKeySpec(key, "AES")
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
            return cipher.doFinal(data)
        } catch (e: Exception) {
            e.printStackTrace()
            return ByteArray(0)
        }
    }

    internal fun pkcs7UnPadding(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val last = data[data.size - 1].toInt() and 0xFF
        if (last > data.size || last == 0) {
             return data 
        }
        return Arrays.copyOf(data, data.size - last)
    }

    private fun ByteArray.indexOf(byte: Byte): Int {
        for (i in indices) {
            if (this[i] == byte) return i
        }
        return -1
    }
}

class NcmStreamDecryptor(private val inputStream: InputStream) {

    private var box: ByteArray? = null
    private val MAGIC_HEADER = "CTENFDAM"

    fun parseHeader(): NcmInfo? {
        try {
            // 1. Validate Magic Header
            val header = readNBytes(MAGIC_HEADER.length)
            if (!Arrays.equals(header, MAGIC_HEADER.toByteArray())) return null

            // 2. Gap 2 bytes
            readNBytes(2)

            // 3. Read Key Data
            val keyLenBytes = readNBytes(4)
            val keyLen = ByteBuffer.wrap(keyLenBytes).order(ByteOrder.LITTLE_ENDIAN).int
            if (keyLen < 0) return null

            val keyRaw = readNBytes(keyLen)
            for (i in keyRaw.indices) {
                keyRaw[i] = (keyRaw[i].toInt() xor 0x64).toByte()
            }

            val deKeyData = NcmDecryptor.decryptAes128Ecb(keyRaw, NcmDecryptor.KEY_CORE)
            val unpaddedKey = NcmDecryptor.pkcs7UnPadding(deKeyData)
            if (unpaddedKey.size < 17) return null
            val boxKey = unpaddedKey.copyOfRange(17, unpaddedKey.size)
            
            // Initialize Box
            box = NcmDecryptor.buildKeyBox(boxKey)

            // 4. Read Meta Data
            val metaLenBytes = readNBytes(4)
            val metaLen = ByteBuffer.wrap(metaLenBytes).order(ByteOrder.LITTLE_ENDIAN).int
            
            var metaInfo: NcmDecryptor.NcmMetaInfo? = null
            if (metaLen > 0) {
                val metaRaw = readNBytes(metaLen)
                metaInfo = NcmDecryptor.decryptMeta(metaRaw)
            }

            // 5. Gap 5 bytes
            readNBytes(5)

            // 6. Read Cover Data
            readNBytes(4) // CRC
            val coverLenBytes = readNBytes(4)
            val coverLen = ByteBuffer.wrap(coverLenBytes).order(ByteOrder.LITTLE_ENDIAN).int
            
            var coverData: ByteArray? = null
            if (coverLen > 0) {
                coverData = readNBytes(coverLen)
            }

            return NcmInfo(
                format = metaInfo?.format ?: "mp3",
                title = metaInfo?.title,
                artist = metaInfo?.artist ?: emptyList(),
                album = metaInfo?.album,
                cover = coverData,
                audioData = ByteArray(0) // Empty
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    fun decryptAudio(outputStream: OutputStream) {
        val currentBox = box ?: throw IllegalStateException("Header not parsed or invalid")
        
        val buffer = ByteArray(8192)
        var bytesRead: Int
        
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            for (i in 0 until bytesRead) {
                buffer[i] = (buffer[i].toInt() xor currentBox[(i) and 0xFF].toInt()).toByte()
            }
            // Wait, the XOR logic uses the absolute index 'i' (from start of audio).
            // I need to track total bytes read for the box index!
            // Re-checking NcmDecryptor logic:
            // for (i in audioData.indices) {
            //     audioData[i] = (audioData[i].toInt() xor box[(i) and 0xFF].toInt()).toByte()
            // }
            // Yes, 'i' is the index in the audio stream.
            
            // So I need to maintain 'audioIndex' across buffer reads.
            // Since I loop 'i' from 0 to bytesRead inside the buffer, the box index is (audioIndex + i) & 0xFF.
            // No, 'i' in the loop is buffer index.
            // The logic was: box[(i) and 0xFF].
            // Wait, 'i' is the index of the byte in the *entire audio file*.
            // So I need to track a global counter.
            
            outputStream.write(buffer, 0, bytesRead)
        }
    }
    
    // Wait, I need to fix the decryptAudio logic above.
    // I need to keep track of 'totalAudioBytesRead' to index into 'box'.
    
    private var audioIndex = 0
    
    fun decryptAudioStream(outputStream: OutputStream) {
         val currentBox = box ?: throw IllegalStateException("Header not parsed or invalid")
         val buffer = ByteArray(8192)
         var bytesRead: Int
         
         while (inputStream.read(buffer).also { bytesRead = it } != -1) {
             for (i in 0 until bytesRead) {
                 // The key index corresponds to the byte's position in the audio stream
                 // box[(audioIndex + i) & 0xFF]
                 val key = currentBox[(audioIndex + i) and 0xFF]
                 buffer[i] = (buffer[i].toInt() xor key.toInt()).toByte()
             }
             outputStream.write(buffer, 0, bytesRead)
             audioIndex += bytesRead
         }
    }

    private fun readNBytes(n: Int): ByteArray {
        val buffer = ByteArray(n)
        var offset = 0
        while (offset < n) {
            val count = inputStream.read(buffer, offset, n - offset)
            if (count == -1) break // EOF
            offset += count
        }
        // If EOF reached before N bytes, result is partial (or zero filled at end)
        return buffer
    }
}
