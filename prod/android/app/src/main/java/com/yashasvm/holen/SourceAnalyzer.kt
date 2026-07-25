package com.yashasvm.holen

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

class SourceAnalyzer(private val engine: YtDlpEngine) {
    suspend fun analyze(rawUrl: String): SourceAnalysis = withContext(Dispatchers.IO) {
        val url = validateHttpsUrl(rawUrl)
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
        var url = rawUrl
        repeat(MAX_REDIRECTS + 1) { redirect ->
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (method == "GET") connection.setRequestProperty("Range", "bytes=0-0")
            val status = connection.responseCode
            if (status !in REDIRECT_CODES) {
                return ProbeResult(
                    finalUrl = url,
                    status = status,
                    mimeType = connection.contentType?.substringBefore(';')?.trim()?.lowercase(),
                    contentDisposition = connection.getHeaderField("Content-Disposition"),
                    contentLength = connection.getHeaderField("Content-Range")
                        ?.substringAfterLast('/', "")
                        ?.toLongOrNull()
                        ?: connection.contentLengthLong.takeIf { it >= 0 },
                ).also { connection.disconnect() }
            }
            if (redirect == MAX_REDIRECTS) {
                connection.disconnect()
                throw IOException("Too many redirects.")
            }
            val location = connection.getHeaderField("Location")
                ?: throw IOException("Redirect response had no destination.")
            val next = URI(url).resolve(location).toString()
            connection.disconnect()
            url = validateHttpsUrl(next)
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
        private const val TIMEOUT_MS = 20_000
        private const val USER_AGENT = "Holen Android/1"
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        fun isDirectFile(contentDisposition: String?, mimeType: String?): Boolean {
            if (contentDisposition?.contains("attachment", ignoreCase = true) == true) return true
            val type = mimeType?.substringBefore(';')?.trim()?.lowercase() ?: return false
            return type !in setOf("text/html", "application/xhtml+xml")
        }
    }
}
