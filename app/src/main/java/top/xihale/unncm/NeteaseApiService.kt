package top.xihale.unncm

import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import top.xihale.unncm.utils.Logger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * Extended metadata that includes lyrics and cover data
 */
data class ExtendedMusicMetadata(
    val metadata: MusicMetadata,
    val id: Long,
    val coverUrl: String?,
    val duration: Long,
    val lyrics: String?,
    val coverData: ByteArray?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ExtendedMusicMetadata
        return metadata == other.metadata && id == other.id
    }

    override fun hashCode(): Int {
        var result = metadata.hashCode()
        result = 31 * result + id.hashCode()
        return result
    }
}

/**
 * Temporary search result that includes all fields needed for API processing
 */
private data class SongSearchResult(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val coverUrl: String?,
    val duration: Long,
    val metadata: MusicMetadata
)

object NeteaseApiService {
    private val logger = Logger.withTag("NeteaseApiService")
    
    private val client = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply {
            maxRequests = 16
            maxRequestsPerHost = 4
        })
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private const val BASE_URL = "https://music.163.com/weapi"
    private const val METADATA_CACHE_MAX_ENTRIES = 256

    private object CryptoConstants {
        const val PRESET_KEY = "0CoJUm6Qyw8W8jud"
        const val IV = "0102030405060708"
        const val SECRET_KEY = "a8LWv2uAtXjzSfkQ"
        const val ENC_SEC_KEY = "2d48fd9fb8e58bc9c1f14a7bda1b8e49a3520a67a2300a1f73766caee29f2411c5350bceb15ed196ca963d6a6d0b61f3734f0a0f4a172ad853f16dd06018bc5ca8fb640eaa8decd1cd41f66e166cea7a3023bd63960e656ec97751cfc7ce08d943928e9db9b35400ff3d138bda1ab511a06fbee75585191cabe0e6e63f7350d6"
    }

    private data class MetadataCacheKey(
        val keyword: String,
        val fetchCover: Boolean,
        val fetchLyrics: Boolean
    )

    private val metadataCache = object : LinkedHashMap<MetadataCacheKey, ExtendedMusicMetadata>(
        METADATA_CACHE_MAX_ENTRIES,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MetadataCacheKey, ExtendedMusicMetadata>?): Boolean {
            return size > METADATA_CACHE_MAX_ENTRIES
        }
    }

    private val cacheMutex = Mutex()
    private val inFlightMutex = Mutex()
    private val inFlightRequests = mutableMapOf<MetadataCacheKey, Deferred<Result<ExtendedMusicMetadata>>>()

    suspend fun getCompleteMetadata(
        keyword: String,
        fetchCover: Boolean = true,
        fetchLyrics: Boolean = true
    ): Result<ExtendedMusicMetadata> = coroutineScope {
        val cacheKey = MetadataCacheKey(normalizeKeyword(keyword), fetchCover, fetchLyrics)

        getCachedMetadata(cacheKey)?.let {
            logger.d("Metadata cache hit: '${cacheKey.keyword}'")
            return@coroutineScope Result.success(it)
        }

        val (deferred, isOwner) = inFlightMutex.withLock {
            val existing = inFlightRequests[cacheKey]
            if (existing != null) {
                existing to false
            } else {
                val created = async(Dispatchers.IO) {
                    fetchCompleteMetadataInternal(keyword, fetchCover, fetchLyrics)
                }
                inFlightRequests[cacheKey] = created
                created to true
            }
        }

        try {
            val result = deferred.await()
            if (isOwner && result.isSuccess) {
                putCachedMetadata(cacheKey, result.getOrThrow())
            }
            result
        } finally {
            if (isOwner) {
                inFlightMutex.withLock {
                    inFlightRequests.remove(cacheKey)
                }
            }
        }
    }

    private suspend fun fetchCompleteMetadataInternal(
        keyword: String,
        fetchCover: Boolean,
        fetchLyrics: Boolean
    ): Result<ExtendedMusicMetadata> = coroutineScope {
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

            val extendedMetadata = ExtendedMusicMetadata(
                metadata = searchResult.metadata,
                id = searchResult.id,
                coverUrl = searchResult.coverUrl,
                duration = searchResult.duration,
                lyrics = lyrics,
                coverData = coverData
            )

            Result.success(extendedMetadata)
        } catch (e: Exception) {
            logger.e("Metadata fetch failed for: $keyword", e)
            Result.failure(e)
        }
    }

    private fun normalizeKeyword(keyword: String): String {
        return keyword.trim().lowercase()
    }

    private fun ExtendedMusicMetadata.safeCopy(): ExtendedMusicMetadata {
        return copy(coverData = coverData?.copyOf())
    }

    private suspend fun getCachedMetadata(cacheKey: MetadataCacheKey): ExtendedMusicMetadata? {
        return cacheMutex.withLock {
            metadataCache[cacheKey]?.safeCopy()
        }
    }

    private suspend fun putCachedMetadata(cacheKey: MetadataCacheKey, metadata: ExtendedMusicMetadata) {
        cacheMutex.withLock {
            metadataCache[cacheKey] = metadata.safeCopy()
        }
    }

    private fun searchSong(keyword: String): SongSearchResult? {
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

            val metadata = MusicMetadata(
                title = song.getString("name"),
                artist = artistName,
                album = albumObj?.optString("name") ?: ""
            )

            return SongSearchResult(
                id = song.getLong("id"),
                title = song.getString("name"),
                artist = artistName,
                album = albumObj?.optString("name") ?: "",
                coverUrl = albumObj?.optString("picUrl")?.replace("http://", "https://")?.let { toOptimizedCoverUrl(it) },
                duration = song.optLong("dt", 0),
                metadata = metadata
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

    private fun toOptimizedCoverUrl(url: String): String {
        if (url.contains("param=")) return url
        val separator = if (url.contains("?")) "&" else "?"
        return "$url${separator}param=500y500"
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
        val pattern = Regex("""\[(\d+):(\d+)(?:\.(\d+))?]""")
        
        fun parse(text: String, priority: Int): List<Line> = text.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                pattern.find(line)?.let { match ->
                    val (min, sec, msStr) = match.destructured
                    val ms = if (msStr.isEmpty()) 0L else msStr.padEnd(3, '0').take(3).toLong()
                    val time = min.toLong() * 60000 + sec.toLong() * 1000 + ms
                    Line(time, priority, line)
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
