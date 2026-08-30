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
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
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
        // These names are deliberately not derived from the response. A hostile
        // Content-Disposition value must never collide with resumable state.
        val part = File(directory, PART_FILE_NAME)
        val validatorFile = File(directory, RESUME_VALIDATOR_FILE_NAME)
        var existing = part.takeIf(File::exists)?.length() ?: 0L
        var resumeState = if (existing > 0) readResumeState(validatorFile) else null
        if (existing > 0 && resumeState == null) {
            // Legacy or incomplete state cannot be resumed safely because a changed remote
            // object could otherwise be appended to stale bytes.
            part.delete()
            existing = 0
        }

        var connection = open(
            job.sourceUrl,
            existing.takeIf { it > 0 },
            resumeState,
        )
        var attemptedResume = connection.request.header("Range") != null
        var completedResume = existing > 0 && attemptedResume && isCompletedRangeResponse(
            existing,
            connection.code,
            connection.header("Content-Range"),
        )
        if (existing > 0 && !completedResume && !(attemptedResume && shouldAppend(
                existing,
                connection.code,
                connection.header("Content-Range"),
            ))
        ) {
            if (connection.code == HttpURLConnection.HTTP_OK) {
                // A failed If-Range condition, or a redirect to a different resource, returns
                // the complete representation. Reuse it and restart locally.
                part.delete()
                existing = 0
                resumeState = null
            } else {
                connection.close()
                part.delete()
                existing = 0
                resumeState = null
                connection = open(job.sourceUrl, null, null)
                attemptedResume = false
            }
            completedResume = false
        }

        try {
            val responseCode = connection.code
            if (!isAcceptedTransferResponse(completedResume, attemptedResume, responseCode)) {
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
                val state = createResumeState(
                    connection.request.url.toString(),
                    connection.header("ETag"),
                    connection.header("Last-Modified"),
                    connection.header("Date"),
                )
                if (state != null) {
                    validatorFile.writeText(encodeResumeState(state))
                } else {
                    validatorFile.delete()
                }
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
            validatorFile.delete()
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

    private fun open(rawUrl: String, rangeStart: Long?, resumeState: ResumeState?): Response {
        var endpoint = resolvePublicHttpsEndpoint(rawUrl)
        repeat(MAX_REDIRECTS + 1) { redirect ->
            val request = Request.Builder()
                .url(endpoint.url)
                .header("User-Agent", USER_AGENT)
                // Keep persisted byte offsets in the same representation across the initial
                // transfer and later Range requests; transparent gzip would make them differ.
                .header("Accept-Encoding", "identity")
                .apply {
                    val target = endpoint.url.toString()
                    if (rangeStart != null && resumeState != null && resumeTargetMatches(resumeState.resourceUrl, target)) {
                        header("Range", "bytes=$rangeStart-")
                        header("If-Range", resumeState.validator)
                    }
                }
                .build()
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
        internal data class ResumeState(val resourceUrl: String, val validator: String)

        // Larger reads reduce Java/Kotlin stream overhead on fast mobile connections while
        // remaining small enough to avoid meaningful memory pressure on older devices.
        private const val COPY_BUFFER_SIZE = 256 * 1024
        // Keep the established name so installs upgrading from earlier builds can detect and
        // safely discard old unscoped resume metadata rather than trusting it.
        private const val PART_FILE_NAME = "download.part"
        private const val RESUME_VALIDATOR_FILE_NAME = "download.resume"
        private const val MAX_REDIRECTS = 5
        private const val TIMEOUT_MS = 20_000
        private const val USER_AGENT = "Holen Android/1"
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        internal fun isAcceptedTransferResponse(
            completedResume: Boolean,
            attemptedResume: Boolean,
            responseCode: Int,
        ): Boolean = completedResume ||
            responseCode == HttpURLConnection.HTTP_OK ||
            (attemptedResume && responseCode == HttpURLConnection.HTTP_PARTIAL)

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

        internal fun selectResumeValidator(
            etag: String?,
            lastModified: String? = null,
            responseDate: String? = null,
        ): String? {
            val trimmedEtag = etag?.trim()
            val strongEtag = trimmedEtag?.takeIf(::isStrongEtag)
            if (strongEtag != null) return strongEtag
            // RFC 9110 only allows an HTTP-date If-Range validator when there is no entity tag
            // for the representation. Do not silently replace a weak or malformed ETag.
            if (!etag.isNullOrBlank()) return null

            val modified = lastModified?.trim()?.takeIf(::isSafeHeaderValue) ?: return null
            val sent = responseDate?.trim()?.takeIf(::isSafeHeaderValue) ?: return null
            val modifiedInstant = parseHttpDate(modified) ?: return null
            val sentInstant = parseHttpDate(sent) ?: return null
            // A cached Last-Modified value can be treated as a strong validator when the
            // response Date is at least one second later (RFC 9110 section 8.8.2.2).
            return modified.takeIf { sentInstant.epochSecond - modifiedInstant.epochSecond >= 1 }
        }

        private fun isStrongEtag(value: String): Boolean =
            isSafeHeaderValue(value) && value.startsWith('"') && value.endsWith('"') &&
                !value.startsWith("W/")

        private fun isSafeHeaderValue(value: String): Boolean =
            value.length <= 256 && '\r' !in value && '\n' !in value

        private fun parseHttpDate(value: String) = runCatching {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        }.getOrNull()

        internal fun createResumeState(
            resourceUrl: String,
            etag: String?,
            lastModified: String? = null,
            responseDate: String? = null,
        ): ResumeState? {
            val validator = selectResumeValidator(etag, lastModified, responseDate) ?: return null
            return createPersistedResumeState(resourceUrl, validator)
        }

        private fun createPersistedResumeState(resourceUrl: String, validator: String): ResumeState? {
            val target = resourceUrl.trim().takeIf {
                it.length <= 2_048 && it.startsWith("https://") && '\r' !in it && '\n' !in it
            } ?: return null
            val safeValidator = validator.trim().takeIf {
                isStrongEtag(it) || (isSafeHeaderValue(it) && parseHttpDate(it) != null)
            } ?: return null
            return ResumeState(target, safeValidator)
        }

        internal fun resumeTargetMatches(savedTarget: String, requestTarget: String): Boolean =
            savedTarget == requestTarget

        private fun encodeResumeState(state: ResumeState): String =
            "${state.resourceUrl}\n${state.validator}"

        private fun readResumeState(file: File): ResumeState? = runCatching {
            val lines = file.takeIf(File::isFile)?.readLines() ?: return@runCatching null
            if (lines.size != 2) return@runCatching null
            createPersistedResumeState(lines[0], lines[1])
        }.getOrNull()

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
            return if (sanitized == PART_FILE_NAME || sanitized == RESUME_VALIDATOR_FILE_NAME) {
                "download-$sanitized"
            } else {
                sanitized
            }
        }
    }
}
