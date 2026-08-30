package com.yashasvm.holen

import java.io.File
import java.io.IOException
import java.net.ProtocolException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

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
            delay(DirectDownloadRetryPolicy.backoffMillis(retriesUsed))
            if (isCancelled()) throw CancellationException("Download cancelled")
            retriesUsed++
        }
    }
}

internal object DirectDownloadRetryPolicy {
    private const val MAX_RETRIES = 2
    private const val BASE_BACKOFF_MS = 1_000L
    private val RETRYABLE_HTTP_CODES = setOf(408, 500, 502, 503, 504)

    fun shouldRetry(error: Throwable, retriesUsed: Int): Boolean {
        if (retriesUsed >= MAX_RETRIES || error !is IOException) return false
        if (error is SSLException || error is ProtocolException) return false

        val message = error.message.orEmpty()
        if (
            message.equals("Too many redirects.", ignoreCase = true) ||
            message.equals("Redirect response had no destination.", ignoreCase = true)
        ) {
            return false
        }

        val status = message
            .takeIf { it.startsWith("Network response ", ignoreCase = true) }
            ?.substringAfter("Network response ")
            ?.takeWhile(Char::isDigit)
            ?.toIntOrNull()
        return status?.let(RETRYABLE_HTTP_CODES::contains) ?: true
    }

    fun backoffMillis(retriesUsed: Int): Long =
        BASE_BACKOFF_MS shl retriesUsed.coerceIn(0, MAX_RETRIES - 1)
}
