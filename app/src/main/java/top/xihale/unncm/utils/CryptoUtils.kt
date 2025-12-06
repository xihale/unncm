package top.xihale.unncm.utils

import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {
    
    fun decryptAes128Ecb(data: ByteArray, key: ByteArray): ByteArray {
        return try {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            val keySpec = SecretKeySpec(key, "AES")
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
            cipher.doFinal(data)
        } catch (e: Exception) {
            // Log or rethrow? For now return empty to match original behavior but it's risky.
            // Better to throw to let caller handle it.
            throw RuntimeException("AES decryption failed", e)
        }
    }

    fun pkcs7UnPadding(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val last = data[data.size - 1].toInt() and 0xFF
        if (last > data.size || last == 0) {
             return data 
        }
        return Arrays.copyOf(data, data.size - last)
    }

    fun buildKeyBox(key: ByteArray): ByteArray {
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
}
