package com.yashasvm.holen

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

class DirectDownloader {
    private val cancelled = AtomicBoolean(false)

    @Volatile
    private var activeConnection: HttpURLConnection? = null

    suspend fun download(
        job: DownloadJob,
        directory: File,
        onProgress: suspend (TransferProgress) -> Unit,
    ): StagedDownload = withContext(Dispatchers.IO) {
        cancelled.set(false)
        directory.mkdirs()
        val part = File(directory, "download.part")
        var existing = part.takeIf(File::exists)?.length() ?: 0L
        var connection = open(job.sourceUrl, existing.takeIf { it > 0 })
        activeConnection = connection
        if (existing > 0 && connection.responseCode != HttpURLConnection.HTTP_PARTIAL) {
            connection.disconnect()
            part.delete()
            existing = 0
            connection = open(job.sourceUrl, null)
            activeConnection = connection
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode !in setOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL)) {
                throw IOException("Network response $responseCode")
            }
            val total = totalLength(connection, existing)
            val disposition = connection.getHeaderField("Content-Disposition")
            val suggested = fileNameFromDisposition(disposition)
                ?: URI(connection.url.toString()).path.substringAfterLast('/').ifBlank { job.title }
            val fileName = sanitizeFileName(suggested)
            val mimeType = OutputStore.mimeTypeFor(fileName, connection.contentType?.substringBefore(';'))
            var downloaded = existing
            var lastBytes = existing
            var lastWrite = System.currentTimeMillis()

            connection.inputStream.use { input ->
                FileOutputStream(part, existing > 0).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        if (cancelled.get()) throw CancellationException("Download cancelled")
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastWrite >= 1_000) {
                            val speed = ((downloaded - lastBytes) * 1_000L / (now - lastWrite))
                                .coerceAtLeast(0)
                            val remaining = total?.minus(downloaded)?.coerceAtLeast(0)
                            onProgress(
                                TransferProgress(
                                    percent = total?.let {
                                        (downloaded * 100 / it.coerceAtLeast(1)).toInt()
                                    } ?: 0,
                                    bytesDownloaded = downloaded,
                                    totalBytes = total,
                                    speedBytesPerSecond = speed,
                                    etaSeconds = remaining?.let { if (speed > 0) it / speed else null },
                                ),
                            )
                            lastWrite = now
                            lastBytes = downloaded
                        }
                    }
                    output.fd.sync()
                }
            }
            if (total != null && downloaded != total) {
                throw IOException("Network transfer ended before the expected byte count.")
            }
            onProgress(TransferProgress(100, downloaded, total ?: downloaded, null, 0))
            val completed = File(directory, fileName)
            if (completed.exists()) completed.delete()
            if (!part.renameTo(completed)) throw IOException("Could not finalize the staged file.")
            StagedDownload(completed, fileName, mimeType)
        } finally {
            activeConnection = null
            connection.disconnect()
        }
    }

    fun cancel() {
        cancelled.set(true)
        activeConnection?.disconnect()
    }

    private fun open(rawUrl: String, rangeStart: Long?): HttpURLConnection {
        var url = validateHttpsUrl(rawUrl)
        repeat(MAX_REDIRECTS + 1) { redirect ->
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            rangeStart?.let { connection.setRequestProperty("Range", "bytes=$it-") }
            val code = connection.responseCode
            if (code !in REDIRECT_CODES) return connection
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

    companion object {
        private const val MAX_REDIRECTS = 5
        private const val TIMEOUT_MS = 20_000
        private const val USER_AGENT = "Holen Android/1"
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        fun shouldAppend(existingBytes: Long, responseCode: Int): Boolean =
            existingBytes > 0 && responseCode == HttpURLConnection.HTTP_PARTIAL

        fun totalLength(connection: HttpURLConnection, existingBytes: Long): Long? {
            val rangeTotal = connection.getHeaderField("Content-Range")
                ?.substringAfterLast('/', "")
                ?.toLongOrNull()
            if (rangeTotal != null) return rangeTotal
            val length = connection.contentLengthLong.takeIf { it >= 0 } ?: return null
            return if (connection.responseCode == HttpURLConnection.HTTP_PARTIAL) {
                existingBytes + length
            } else {
                length
            }
        }

        fun fileNameFromDisposition(header: String?): String? {
            if (header.isNullOrBlank()) return null
            Regex("""filename\*=UTF-8''([^;]+)""", RegexOption.IGNORE_CASE)
                .find(header)?.groupValues?.get(1)?.let {
                    return URLDecoder.decode(it, StandardCharsets.UTF_8.name())
                }
            return Regex("""filename="?([^";]+)"?""", RegexOption.IGNORE_CASE)
                .find(header)?.groupValues?.get(1)
        }
    }
}
