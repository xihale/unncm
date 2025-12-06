package top.xihale.unncm

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import top.xihale.unncm.utils.Logger
import java.math.BigInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object NeteaseApiService {
    private val logger = Logger.withTag("NeteaseApiService")
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private const val BASE_URL = "https://music.163.com/weapi"

    private object CryptoConstants {
        const val PRESET_KEY = "0CoJUm6Qyw8W8jud"
        const val IV = "0102030405060708"
        const val SECRET_KEY = "a8LWv2uAtXjzSfkQ"
        const val PUB_KEY_MODULUS = "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbca2dc37e78ed629dc22e423eeee65606e79675343f46c7a1f5095f5fd70dbaa5839da6bf42f7258509a831f1741de61fd59556fa4fd274aa35fd97d87"
        const val PUB_KEY_EXPONENT = "10001"
        const val ENC_SEC_KEY = "2d48fd9fb8e58bc9c1f14a7bda1b8e49a3520a67a2300a1f73766caee29f2411c5350bceb15ed196ca963d6a6d0b61f3734f0a0f4a172ad853f16dd06018bc5ca8fb640eaa8decd1cd41f66e166cea7a3023bd63960e656ec97751cfc7ce08d943928e9db9b35400ff3d138bda1ab511a06fbee75585191cabe0e6e63f7350d6"
    }

    suspend fun getCompleteMetadata(
        keyword: String, 
        fetchCover: Boolean = true, 
        fetchLyrics: Boolean = true
    ): Result<MusicMetadata> = coroutineScope {
        try {
            val searchResult = withContext(Dispatchers.IO) { searchSong(keyword) }
                ?: return@coroutineScope Result.failure(Exception("Search returned null for: $keyword"))

            logger.i("Found song: '${searchResult.title}' (ID: ${searchResult.id})")

            val lyricsDeferred = async(Dispatchers.IO) {
                if (fetchLyrics) fetchLyrics(searchResult.id) else null
            }

            val coverDeferred = async(Dispatchers.IO) {
                if (fetchCover && searchResult.coverUrl != null) {
                    fetchCoverImage(searchResult.coverUrl)
                } else null
            }

            val lyrics = lyricsDeferred.await()
            val coverData = coverDeferred.await()

            val metadata = searchResult.copy(
                lyrics = lyrics,
                coverData = coverData
            )

            Result.success(metadata)
        } catch (e: Exception) {
            logger.e("Metadata fetch failed for: $keyword", e)
            Result.failure(e)
        }
    }

    private fun searchSong(keyword: String): MusicMetadata? {
        try {
            val responseJson = postWeApi(
                url = "$BASE_URL/cloudsearch/pc",
                data = mapOf("s" to keyword, "type" to 1, "limit" to 1, "offset" to 0)
            ) ?: return null

            val songs = responseJson.optJSONObject("result")?.optJSONArray("songs")
            if (songs == null || songs.length() == 0) return null

            val song = songs.getJSONObject(0)
            val albumObj = song.optJSONObject("al")
            
            // Parse Artists
            val artistsJson = song.optJSONArray("ar")
            val artistName = if (artistsJson != null && artistsJson.length() > 0) {
                (0 until artistsJson.length()).joinToString("/") { i ->
                    artistsJson.getJSONObject(i).optString("name")
                }
            } else "Unknown"

            return MusicMetadata(
                id = song.getLong("id"),
                title = song.getString("name"),
                artist = artistName,
                album = albumObj?.optString("name") ?: "",
                coverUrl = albumObj?.optString("picUrl")?.replace("http://", "https://"),
                duration = song.optLong("dt", 0),
                lyrics = null,
                coverData = null
            )
        } catch (e: Exception) {
            logger.w("Search error for '$keyword': ${e.message}")
            return null
        }
    }

    private fun fetchLyrics(songId: Long): String? {
        try {
            val responseJson = postWeApi(
                url = "$BASE_URL/song/lyric",
                data = mapOf("id" to songId, "lv" to -1, "tv" to -1)
            ) ?: return null
            
            val lrc = responseJson.optJSONObject("lrc")?.optString("lyric") ?: ""
            val tlyric = responseJson.optJSONObject("tlyric")?.optString("lyric")
            
            return mergeLyrics(lrc, tlyric)
        } catch (e: Exception) {
            logger.w("Lyrics fetch error for $songId: ${e.message}")
            return null
        }
    }

    private fun fetchCoverImage(url: String): ByteArray? {
        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0")
                .build()
                
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    return response.body?.bytes()
                }
            }
        } catch (e: Exception) {
            logger.w("Cover fetch error ($url): ${e.message}")
        }
        return null
    }

    // --- Helper Methods ---

    private fun postWeApi(url: String, data: Map<String, Any>): JSONObject? {
        val jsonString = JSONObject(data).toString()
        val (params, encSecKey) = encryptWeApi(jsonString)

        val requestBody = "params=$params&encSecKey=$encSecKey"
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())
            
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            return JSONObject(body)
        }
    }

    private fun mergeLyrics(lrc: String, tlyric: String?): String {
        if (tlyric.isNullOrEmpty()) return lrc
        if (lrc.isEmpty()) return tlyric
        
        data class Line(val time: Long, val priority: Int, val content: String)
        
        fun parse(text: String, priority: Int): List<Line> {
            return text.lines().filter { it.isNotBlank() }.mapNotNull { line ->
                try {
                    val start = line.indexOf('[')
                    val end = line.indexOf(']')
                    if (start != -1 && end != -1) {
                        val timeStr = line.substring(start + 1, end)
                        val parts = timeStr.split(":")
                        if (parts.size == 2) {
                             val min = parts[0].toLong()
                             val secParts = parts[1].split(".")
                             val sec = secParts[0].toLong()
                             val msStr = if (secParts.size > 1) secParts[1] else "0"
                             val ms = if (msStr.length == 2) msStr.toLong() * 10 
                                      else if (msStr.length == 1) msStr.toLong() * 100
                                      else msStr.take(3).padEnd(3, '0').toLong()
                             val time = min * 60000 + sec * 1000 + ms
                             return@mapNotNull Line(time, priority, line)
                        }
                    }
                } catch (_: Exception) { }
                null
            }
        }
        
        val list1 = parse(lrc, 0)
        val list2 = parse(tlyric, 1)
        
        return (list1 + list2).sortedWith(compareBy({ it.time }, { it.priority }))
            .joinToString("\n") { it.content }
    }

    // --- Encryption ---

    private fun encryptWeApi(text: String): Pair<String, String> {
        val params1 = aesEncrypt(text, CryptoConstants.PRESET_KEY, CryptoConstants.IV)
        val paramsBase64 = aesEncrypt(params1, CryptoConstants.SECRET_KEY, CryptoConstants.IV)
        val params = java.net.URLEncoder.encode(paramsBase64, "UTF-8")
        return Pair(params, CryptoConstants.ENC_SEC_KEY)
    }

    private fun aesEncrypt(text: String, key: String, iv: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(key.toByteArray(), "AES")
        val ivSpec = IvParameterSpec(iv.toByteArray())
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        val encrypted = cipher.doFinal(text.toByteArray())
        return android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
    }

}
