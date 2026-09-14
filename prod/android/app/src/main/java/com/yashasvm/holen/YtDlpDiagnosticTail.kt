package com.yashasvm.holen

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps a small tail of yt-dlp's merged callback stream so failures still carry useful stderr
 * diagnostics when youtubedl-android redirects stderr into stdout for live progress callbacks.
 */
internal class YtDlpDiagnosticTail(
    private val maxChars: Int = 8 * 1024,
) {
    private val buffer = StringBuilder()

    @Synchronized
    fun add(line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.contains(PROGRESS_MARKER)) return

        val boundedLine = trimmed.takeLast(maxChars)
        if (buffer.isNotEmpty()) buffer.append('\n')
        buffer.append(boundedLine)
        if (buffer.length > maxChars) {
            buffer.delete(0, buffer.length - maxChars)
        }
    }

    @Synchronized
    fun snapshot(): String = buffer.toString().trim()
}

private val ytDlpDownloadProcessSequence = AtomicLong()

/**
 * The youtubedl-android wrapper can retain a stale process-map entry after a process has already
 * exited. Reusing a persistent HOLEN job id for a later Retry can then fail with
 * "Process ID already exists" before yt-dlp even starts. A process id only needs to identify one
 * live wrapper invocation, so every execution gets a fresh id instead of reusing the logical job
 * id across retries/recovery.
 */
internal fun nextYtDlpDownloadProcessId(): String =
    "holen-download-${ytDlpDownloadProcessSequence.incrementAndGet()}"

internal fun executeYtDlpDownload(
    request: YoutubeDLRequest,
    processId: String,
    isCancelled: () -> Boolean,
    callback: (Float, Long, String) -> Unit,
) = run {
    val diagnostics = YtDlpDiagnosticTail()
    val executionProcessId = nextYtDlpDownloadProcessId()

    // Cancellation is expressed in HOLEN using the persistent logical job id. Since wrapper
    // executions now intentionally use per-attempt ids, keep a tiny watcher that targets the
    // actual invocation. This also covers service teardown where the blocking wrapper call may
    // not receive another progress callback before Android asks the service to stop.
    val cancellationWatcher = Thread(
        {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    if (isCancelled()) {
                        // Keep polling while cancelled: cancellation can race with Process.start()
                        // and the first destroy attempt may happen just before the wrapper registers
                        // the process in its internal map.
                        YoutubeDL.destroyProcessById(executionProcessId)
                    }
                    Thread.sleep(CANCELLATION_POLL_INTERVAL_MS)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        },
        "holen-ytdlp-cancel-$processId",
    ).apply {
        isDaemon = true
        start()
    }

    try {
        YoutubeDL.execute(request, executionProcessId, true) { percent, eta, line ->
            diagnostics.add(line)
            callback(percent, eta, line)
        }
    } catch (error: Exception) {
        throw withYtDlpDiagnostics(error, diagnostics.snapshot(), isCancelled())
    } finally {
        cancellationWatcher.interrupt()
        // Best-effort final cleanup for cancellation. On affected wrapper versions this may not
        // remove an already-dead stale entry, which is why future attempts always use a new id.
        if (isCancelled()) YoutubeDL.destroyProcessById(executionProcessId)
    }
}

internal fun withYtDlpDiagnostics(
    error: Throwable,
    diagnostics: String,
    cancelled: Boolean,
): Throwable {
    if (error is CancellationException) return error
    if (cancelled) {
        return CancellationException("Download cancelled").apply { initCause(error) }
    }

    val tail = diagnostics.trim()
    if (tail.isEmpty()) return error
    val message = error.message.orEmpty().trim()
    if (message.contains(tail)) return error
    return IOException(
        if (message.isEmpty()) tail else "$message\n$tail",
        error,
    )
}

private const val CANCELLATION_POLL_INTERVAL_MS = 100L
