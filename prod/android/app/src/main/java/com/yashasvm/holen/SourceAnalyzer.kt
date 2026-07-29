package com.yashasvm.holen

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import okhttp3.Request

class SourceAnalyzer(private val engine: YtDlpEngine) {
    suspend fun analyze(rawUrl: String): SourceAnalysis = withContext(Dispatchers.IO) {
        // Validate before handing an extractor-first URL to the media engine too.
        // The direct probe below additionally pins its actual network sockets.
        val url = validatePublicHttpsUrl(rawUrl)
        if (isExtractorFirstHost(URI(url).host)) {
            return@withContext engine.analyze(url)
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
            engine.analyze(url)
        }
    }

    private fun probe(rawUrl: String): ProbeResult {
        var method = "HEAD"
        while (true) {
            val result = request(rawUrl, method)
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

    private fun request(rawUrl: String, method: String): ProbeResult {
        var endpoint = resolvePublicHttpsEndpoint(rawUrl)
        repeat(MAX_REDIRECTS + 1) { redirect ->
            val request = Request.Builder().url(endpoint.url).method(method, null)
                .header("User-Agent", USER_AGENT)
                .apply { if (method == "GET") header("Range", "bytes=0-0") }
                .build()
            val response = pinnedPublicHttpsClient(endpoint, TIMEOUT_MS.toLong()).newCall(request).execute()
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

    companion object {
        private const val MAX_REDIRECTS = 5
        private const val TIMEOUT_MS = 3_000
        private const val USER_AGENT = "Holen Android/1"
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        fun isExtractorFirstHost(host: String?): Boolean {
            val normalized = host?.trimEnd('.')?.lowercase() ?: return false
            return normalized == "youtu.be" ||
                normalized == "youtube.com" ||
                normalized.endsWith(".youtube.com")
        }

        fun isDirectFile(contentDisposition: String?, mimeType: String?): Boolean {
            if (contentDisposition?.contains("attachment", ignoreCase = true) == true) return true
            val type = mimeType?.substringBefore(';')?.trim()?.lowercase() ?: return false
            return type !in setOf("text/html", "application/xhtml+xml")
        }
    }
}
