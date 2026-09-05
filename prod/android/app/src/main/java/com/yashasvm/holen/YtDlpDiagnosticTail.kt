package com.yashasvm.holen

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import java.io.IOException

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

internal fun executeYtDlpDownload(
    request: YoutubeDLRequest,
    processId: String,
    isCancelled: () -> Boolean,
    callback: (Float, Long, String) -> Unit,
) = run {
    val diagnostics = YtDlpDiagnosticTail()
    try {
        YoutubeDL.execute(request, processId, true) { percent, eta, line ->
            diagnostics.add(line)
            callback(percent, eta, line)
        }
    } catch (error: Throwable) {
        throw withYtDlpDiagnostics(error, diagnostics.snapshot(), isCancelled())
    }
}

internal fun withYtDlpDiagnostics(
    error: Throwable,
    diagnostics: String,
    cancelled: Boolean,
): Throwable {
    if (error is CancellationException) return error
    if (cancelled) return CancellationException("Download cancelled", error)

    val tail = diagnostics.trim()
    if (tail.isEmpty()) return error
    val message = error.message.orEmpty().trim()
    if (message.contains(tail)) return error
    return IOException(
        if (message.isEmpty()) tail else "$message\n$tail",
        error,
    )
}
