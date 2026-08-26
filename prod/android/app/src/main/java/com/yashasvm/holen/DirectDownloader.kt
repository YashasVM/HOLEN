package com.yashasvm.holen

import android.os.SystemClock
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
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response

class DirectDownloader {
    private val cancelled = AtomicBoolean(false)

    @Volatile
    private var activeCall: Call? = null

    suspend fun download(
        job: DownloadJob,
        directory: File,
        isCancelled: () -> Boolean = { false },
        onProgress: suspend (TransferProgress) -> Unit,
    ): StagedDownload = withContext(Dispatchers.IO) {
        cancelled.set(false)
        if (isCancelled()) throw CancellationException("Download cancelled")
        check(directory.isDirectory || directory.mkdirs()) {
            "Could not prepare private download storage."
        }
        // This name is deliberately not derived from the response. A hostile
        // Content-Disposition value must never collide with the resumable part.
        val part = File(directory, PART_FILE_NAME)
        var existing = part.takeIf(File::exists)?.length() ?: 0L
        var connection = open(job.sourceUrl, existing.takeIf { it > 0 })
        val completedResume = existing > 0 && isCompletedRangeResponse(
            existing,
            connection.code,
            connection.header("Content-Range"),
        )
        if (existing > 0 && !completedResume && !shouldAppend(
                existing,
                connection.code,
                connection.header("Content-Range"),
            )
        ) {
            connection.close()
            part.delete()
            existing = 0
            connection = open(job.sourceUrl, null)
        }

        try {
            val responseCode = connection.code
            if (!completedResume && responseCode !in setOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL)) {
                throw IOException("Network response $responseCode")
            }
            val total = if (completedResume) existing else totalLength(connection, existing)
            val disposition = connection.header("Content-Disposition")
            val suggested = fileNameFromDisposition(disposition)
                ?: URI(job.sourceUrl).path.substringAfterLast('/').ifBlank { job.title }
            val fileName = completionFileName(suggested)
            val mimeType = OutputStore.mimeTypeFor(fileName, connection.header("Content-Type")?.substringBefore(';'))
            var downloaded = existing
            var lastBytes = existing
            var lastWrite = SystemClock.elapsedRealtime()

            if (!completedResume) {
                requireNotNull(connection.body).byteStream().use { input ->
                    FileOutputStream(part, existing > 0).use { output ->
                        val buffer = ByteArray(COPY_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            if (cancelled.get() || isCancelled()) {
                                throw CancellationException("Download cancelled")
                            }
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val now = SystemClock.elapsedRealtime()
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
            }
            if (total != null && downloaded != total) {
                throw IOException("Network transfer ended before the expected byte count.")
            }
            onProgress(TransferProgress(100, downloaded, total ?: downloaded, null, 0))
            val completed = File(directory, fileName)
            if (completed.exists()) completed.delete()
            if (!part.renameTo(completed)) {
                throw StorageException("Could not finalize the staged file.")
            }
            StagedDownload(completed, fileName, mimeType)
        } finally {
            activeCall = null
            connection.close()
        }
    }

    fun cancel() {
        cancelled.set(true)
        activeCall?.cancel()
    }

    private fun open(rawUrl: String, rangeStart: Long?): Response {
        var endpoint = resolvePublicHttpsEndpoint(rawUrl)
        repeat(MAX_REDIRECTS + 1) { redirect ->
            val request = Request.Builder().url(endpoint.url).header("User-Agent", USER_AGENT)
                .apply { rangeStart?.let { header("Range", "bytes=$it-") } }.build()
            val call = pinnedPublicHttpsClient(endpoint, TIMEOUT_MS.toLong()).newCall(request)
            activeCall = call
            val response = call.execute()
            if (response.code !in REDIRECT_CODES) return response
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
            // Resolve, validate, and pin every hop independently. Redirects are
            // attacker-controlled and must not inherit the previous host's trust.
            endpoint = resolvePublicHttpsEndpoint(next)
        }
        error("Unreachable")
    }

    companion object {
        // Larger reads reduce Java/Kotlin stream overhead on fast mobile connections while
        // remaining small enough to avoid meaningful memory pressure on older devices.
        private const val COPY_BUFFER_SIZE = 256 * 1024
        // Keep the established name so installs upgrading from earlier builds
        // can continue an existing direct download.
        private const val PART_FILE_NAME = "download.part"
        private const val MAX_REDIRECTS = 5
        private const val TIMEOUT_MS = 20_000
        private const val USER_AGENT = "Holen Android/1"
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        fun shouldAppend(existingBytes: Long, responseCode: Int): Boolean =
            existingBytes > 0 && responseCode == HttpURLConnection.HTTP_PARTIAL

        fun shouldAppend(
            existingBytes: Long,
            responseCode: Int,
            contentRange: String?,
        ): Boolean {
            if (existingBytes <= 0 || responseCode != HttpURLConnection.HTTP_PARTIAL) return false
            val start = Regex("""bytes\s+(\d+)-""", RegexOption.IGNORE_CASE)
                .find(contentRange.orEmpty())
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull()
            return start == existingBytes
        }

        internal fun isCompletedRangeResponse(
            existingBytes: Long,
            responseCode: Int,
            contentRange: String?,
        ): Boolean {
            if (existingBytes <= 0 || responseCode != 416) return false
            val total = Regex("""^\s*bytes\s+\*/(\d+)\s*$""", RegexOption.IGNORE_CASE)
                .matchEntire(contentRange.orEmpty())
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull()
            return total == existingBytes
        }

        fun totalLength(response: Response, existingBytes: Long): Long? {
            val rangeTotal = response.header("Content-Range")
                ?.substringAfterLast('/', "")
                ?.toLongOrNull()
            if (rangeTotal != null) return rangeTotal
            val length = response.body?.contentLength()?.takeIf { it >= 0 } ?: return null
            return if (response.code == HttpURLConnection.HTTP_PARTIAL) existingBytes + length else length
        }

        fun fileNameFromDisposition(header: String?): String? {
            if (header.isNullOrBlank()) return null
            Regex("""filename\*=UTF-8''([^;]+)""", RegexOption.IGNORE_CASE)
                .find(header)?.groupValues?.get(1)?.let { encoded ->
                    runCatching {
                        URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
                    }.getOrNull()?.let { return it }
                }
            return Regex("""filename="?([^";]+)"?""", RegexOption.IGNORE_CASE)
                .find(header)?.groupValues?.get(1)
        }

        internal fun completionFileName(suggested: String): String {
            val sanitized = sanitizeFileName(suggested)
            return if (sanitized == PART_FILE_NAME) "download-$sanitized" else sanitized
        }
    }
}