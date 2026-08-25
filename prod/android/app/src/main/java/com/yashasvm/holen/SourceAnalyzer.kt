package com.yashasvm.holen

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import okhttp3.Request

class SourceAnalyzer(private val engine: YtDlpEngine) {
    private val quickYoutubeCache = object : LinkedHashMap<String, CachedQuickYoutube>(
        QUICK_YOUTUBE_CACHE_MAX_ENTRIES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, CachedQuickYoutube>,
        ): Boolean = size > QUICK_YOUTUBE_CACHE_MAX_ENTRIES
    }

    suspend fun analyze(
        rawUrl: String,
        mode: AnalysisMode = AnalysisMode.FULL,
        processId: String? = null,
    ): SourceAnalysis = withContext(Dispatchers.IO) {
        // Validate before handing an extractor-first URL to the media engine too.
        // The direct probe below additionally pins its actual network sockets.
        val url = validatePublicHttpsUrl(rawUrl)
        if (isExtractorFirstHost(URI(url).host)) {
            // A share-sheet preview only needs title/channel/thumbnail before the user can
            // choose a format. YouTube's oEmbed response is dramatically lighter than
            // starting the Python + yt-dlp extractor and enumerating every media format.
            // Private/age-restricted/unsupported links automatically fall back to yt-dlp,
            // preserving cookies and the existing extractor behavior.
            if (mode == AnalysisMode.QUICK && !isYoutubePlaylist(url)) {
                quickYoutubeMetadata(url)?.let { return@withContext it }
            }
            return@withContext if (processId == null) {
                engine.analyze(url, mode)
            } else {
                engine.analyze(url, mode, processId)
            }
        }
        val probe = probe(url)
        if (isDirectFile(probe.contentDisposition, probe.mimeType)) {
            val name = sanitizeFileName(
                DirectDownloader.fileNameFromDisposition(probe.contentDisposition)
                    ?: URI(probe.finalUrl).path.substringAfterLast('/').ifBlank { "download" },
            )
            SourceAnalysis.DirectFile(
                sourceUrl = probe.finalUrl,
                title = name,
                fileName = name,
                mimeType = probe.mimeType,
                sizeBytes = probe.contentLength,
            )
        } else {
            if (processId == null) {
                engine.analyze(url, mode)
            } else {
                engine.analyze(url, mode, processId)
            }
        }
    }

    private fun quickYoutubeMetadata(url: String): SourceAnalysis.Media? {
        val now = System.currentTimeMillis()
        synchronized(quickYoutubeCache) {
            quickYoutubeCache[url]?.let { cached ->
                if (cached.expiresAt > now) return cached.media
                quickYoutubeCache.remove(url)
            }
        }

        val fresh = runCatching {
            val encoded = URLEncoder.encode(url, StandardCharsets.UTF_8.name())
            val endpoint = resolvePublicHttpsEndpoint(
                "https://www.youtube.com/oembed?url=$encoded&format=json",
            )
            val request = Request.Builder()
                .url(endpoint.url)
                .header("User-Agent", USER_AGENT)
                .build()
            pinnedPublicHttpsClient(endpoint, QUICK_YOUTUBE_TIMEOUT_MS)
                .newCall(request)
                .execute()
                .use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    val body = response.body?.string()?.takeIf { it.isNotBlank() }
                        ?: return@runCatching null
                    val json = JSONObject(body)
                    val title = json.optString("title").takeIf { it.isNotBlank() }
                        ?: return@runCatching null
                    SourceAnalysis.Media(
                        sourceUrl = url,
                        title = title,
                        uploader = json.optString("author_name").takeIf { it.isNotBlank() },
                        durationSeconds = null,
                        thumbnailUrl = json.optString("thumbnail_url").takeIf { it.isNotBlank() },
                        estimatedSizes = emptyMap(),
                    )
                }
        }.getOrNull()

        synchronized(quickYoutubeCache) {
            quickYoutubeCache[url] = CachedQuickYoutube(
                media = fresh,
                expiresAt = System.currentTimeMillis() + if (fresh == null) {
                    QUICK_YOUTUBE_NEGATIVE_CACHE_TTL_MS
                } else {
                    QUICK_YOUTUBE_CACHE_TTL_MS
                },
            )
        }
        return fresh
    }

    private fun probe(rawUrl: String): ProbeResult {
        val deadlineNanos = System.nanoTime() + PROBE_BUDGET_MS * 1_000_000L
        var method = "HEAD"
        while (true) {
            val result = request(rawUrl, method, deadlineNanos)
            if (method == "HEAD" && result.status in setOf(405, 501)) {
                method = "GET"
                continue
            }
            if (result.status !in 200..299) {
                throw IOException("Network response ${result.status}")
            }
            return result
        }
    }

    private fun request(rawUrl: String, method: String, deadlineNanos: Long): ProbeResult {
        var endpoint = resolvePublicHttpsEndpoint(rawUrl)
        repeat(MAX_REDIRECTS + 1) { redirect ->
            val request = Request.Builder().url(endpoint.url).method(method, null)
                .header("User-Agent", USER_AGENT)
                .apply { if (method == "GET") header("Range", "bytes=0-0") }
                .build()
            val remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L
            if (remainingMillis <= 0) throw IOException("Metadata probe timed out.")
            val response = pinnedPublicHttpsClient(
                endpoint,
                minOf(TIMEOUT_MS.toLong(), remainingMillis),
            ).newCall(request).execute()
            val status = response.code
            if (status !in REDIRECT_CODES) {
                return ProbeResult(
                    finalUrl = endpoint.url,
                    status = status,
                    mimeType = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase(),
                    contentDisposition = response.header("Content-Disposition"),
                    contentLength = response.header("Content-Range")
                        ?.substringAfterLast('/', "")
                        ?.toLongOrNull()
                        ?: response.body?.contentLength()?.takeIf { it >= 0 },
                ).also { response.close() }
            }
            if (redirect == MAX_REDIRECTS) {
                response.close()
                throw IOException("Too many redirects.")
            }
            val location = response.header("Location") ?: run {
                response.close()
                throw IOException("Redirect response had no destination.")
            }
            val next = URI(endpoint.url).resolve(location).toString()
            response.close()
            endpoint = resolvePublicHttpsEndpoint(next)
        }
        error("Unreachable")
    }

    data class ProbeResult(
        val finalUrl: String,
        val status: Int,
        val mimeType: String?,
        val contentDisposition: String?,
        val contentLength: Long?,
    )

    private data class CachedQuickYoutube(
        val media: SourceAnalysis.Media?,
        val expiresAt: Long,
    )

    companion object {
        private const val MAX_REDIRECTS = 5
        private const val TIMEOUT_MS = 3_000
        private const val PROBE_BUDGET_MS = 4_000
        private const val QUICK_YOUTUBE_TIMEOUT_MS = 2_500L
        private const val QUICK_YOUTUBE_CACHE_MAX_ENTRIES = 32
        private const val QUICK_YOUTUBE_CACHE_TTL_MS = 5 * 60_000L
        private const val QUICK_YOUTUBE_NEGATIVE_CACHE_TTL_MS = 30_000L
        private const val USER_AGENT = "Holen Android/1"
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        fun isExtractorFirstHost(host: String?): Boolean {
            val normalized = host?.trimEnd('.')?.lowercase() ?: return false
            return normalized == "youtu.be" ||
                normalized == "youtube.com" ||
                normalized.endsWith(".youtube.com") ||
                normalized == "youtube-nocookie.com" ||
                normalized.endsWith(".youtube-nocookie.com")
        }

        private fun isYoutubePlaylist(url: String): Boolean {
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            if (uri.path.equals("/playlist", ignoreCase = true)) return true
            return uri.rawQuery
                ?.split('&')
                ?.any { parameter -> parameter.substringBefore('=') == "list" }
                ?: false
        }

        fun isDirectFile(contentDisposition: String?, mimeType: String?): Boolean {
            if (contentDisposition?.contains("attachment", ignoreCase = true) == true) return true
            val type = mimeType?.substringBefore(';')?.trim()?.lowercase() ?: return false
            return type !in setOf("text/html", "application/xhtml+xml")
        }
    }
}
