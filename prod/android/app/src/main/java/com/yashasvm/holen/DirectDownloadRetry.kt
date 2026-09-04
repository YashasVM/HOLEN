package com.yashasvm.holen

import java.io.File
import java.io.IOException
import java.net.ProtocolException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal class DirectHttpException(
    val statusCode: Int,
    val retryAfter: String? = null,
) : IOException("Network response $statusCode")

internal suspend fun DirectDownloader.downloadWithRetry(
    job: DownloadJob,
    directory: File,
    isCancelled: () -> Boolean,
    onProgress: suspend (TransferProgress) -> Unit,
): StagedDownload {
    var retriesUsed = 0
    while (true) {
        try {
            return download(job, directory, isCancelled, onProgress)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (isCancelled() || !DirectDownloadRetryPolicy.shouldRetry(error, retriesUsed)) {
                throw error
            }
            delay(DirectDownloadRetryPolicy.backoffMillis(error, retriesUsed))
            if (isCancelled()) throw CancellationException("Download cancelled")
            retriesUsed++
        }
    }
}

internal object DirectDownloadRetryPolicy {
    private const val MAX_RETRIES = 2
    private const val BASE_BACKOFF_MS = 1_000L
    private const val MAX_RETRY_AFTER_MS = 30_000L
    private val RETRYABLE_HTTP_CODES = setOf(408, 500, 502, 503, 504)

    fun shouldRetry(
        error: Throwable,
        retriesUsed: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        if (retriesUsed >= MAX_RETRIES || error !is IOException) return false
        if (error is StorageException || error is SSLException || error is ProtocolException) return false

        val message = error.message.orEmpty()
        if (
            message.equals("Too many redirects.", ignoreCase = true) ||
            message.equals("Redirect response had no destination.", ignoreCase = true)
        ) {
            return false
        }

        if (error is DirectHttpException && error.statusCode == 429) {
            return retryAfterMillis(error.retryAfter, nowMillis) != null
        }

        val status = message
            .takeIf { it.startsWith("Network response ", ignoreCase = true) }
            ?.substringAfter("Network response ")
            ?.takeWhile(Char::isDigit)
            ?.toIntOrNull()
        return status?.let(RETRYABLE_HTTP_CODES::contains) ?: true
    }

    fun backoffMillis(
        error: Throwable,
        retriesUsed: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ): Long {
        if (error is DirectHttpException && error.statusCode == 429) {
            retryAfterMillis(error.retryAfter, nowMillis)?.let { return it }
        }
        return BASE_BACKOFF_MS shl retriesUsed.coerceIn(0, MAX_RETRIES - 1)
    }

    internal fun retryAfterMillis(value: String?, nowMillis: Long): Long? {
        val header = value?.trim()?.takeIf {
            it.length <= 128 && '\r' !in it && '\n' !in it
        } ?: return null

        val delaySeconds = header.toLongOrNull()
        val delayMillis = if (delaySeconds != null) {
            if (delaySeconds < 0 || delaySeconds > MAX_RETRY_AFTER_MS / 1_000L) return null
            delaySeconds * 1_000L
        } else {
            val target = runCatching {
                ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull() ?: return null
            (target - nowMillis).coerceAtLeast(0L)
        }
        return delayMillis.takeIf { it <= MAX_RETRY_AFTER_MS }
    }
}
