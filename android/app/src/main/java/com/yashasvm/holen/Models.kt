package com.yashasvm.holen

import java.net.URI

enum class SourceKind {
    DIRECT_FILE,
    MEDIA,
}

enum class DownloadFormat {
    ORIGINAL,
    BEST_MP4,
    MP4_1080,
    MP4_720,
    AUDIO_M4A,
    AUDIO_MP3,
}

enum class JobStatus {
    QUEUED,
    RUNNING,
    FINALIZING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

sealed interface SourceAnalysis {
    val sourceUrl: String

    data class DirectFile(
        override val sourceUrl: String,
        val title: String,
        val fileName: String,
        val mimeType: String?,
        val sizeBytes: Long?,
    ) : SourceAnalysis

    data class Media(
        override val sourceUrl: String,
        val title: String,
        val uploader: String?,
        val durationSeconds: Long?,
        val thumbnailUrl: String?,
        val estimatedSizes: Map<DownloadFormat, Long?>,
    ) : SourceAnalysis

    data class Playlist(
        override val sourceUrl: String,
        val title: String,
        val uploader: String?,
        val entries: List<PlaylistEntry>,
    ) : SourceAnalysis
}

data class PlaylistEntry(
    val id: String,
    val url: String,
    val title: String,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
)

data class DownloadJob(
    val id: String,
    val sourceUrl: String,
    val sourceKind: SourceKind,
    val format: DownloadFormat,
    val title: String,
    val thumbnailUrl: String?,
    val status: JobStatus,
    val progress: Int,
    val bytesDownloaded: Long?,
    val totalBytes: Long?,
    val speedBytesPerSecond: Long?,
    val etaSeconds: Long?,
    val outputUri: String?,
    val fileName: String?,
    val mimeType: String?,
    val errorMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

data class TransferProgress(
    val percent: Int,
    val bytesDownloaded: Long?,
    val totalBytes: Long?,
    val speedBytesPerSecond: Long?,
    val etaSeconds: Long?,
)

data class StagedDownload(
    val file: java.io.File,
    val fileName: String,
    val mimeType: String,
)

fun validateHttpsUrl(raw: String): String {
    val value = raw.trim()
    require(value.length in 1..4096) { "Enter an HTTPS URL up to 4,096 characters." }
    val uri = runCatching { URI(value) }.getOrNull()
        ?: throw IllegalArgumentException("Enter a valid HTTPS URL.")
    require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
        "Only public HTTPS links are supported."
    }
    require(uri.userInfo == null) { "Links containing credentials are not supported." }
    return uri.toASCIIString()
}

fun JobStatus.canTransitionTo(next: JobStatus): Boolean = when (this) {
    JobStatus.QUEUED -> next in setOf(JobStatus.RUNNING, JobStatus.CANCELLED)
    JobStatus.RUNNING -> next in setOf(
        JobStatus.QUEUED,
        JobStatus.FINALIZING,
        JobStatus.FAILED,
        JobStatus.CANCELLED,
    )
    JobStatus.FINALIZING -> next in setOf(
        JobStatus.QUEUED,
        JobStatus.COMPLETED,
        JobStatus.FAILED,
        JobStatus.CANCELLED,
    )
    JobStatus.FAILED, JobStatus.CANCELLED -> next == JobStatus.QUEUED
    JobStatus.COMPLETED -> false
}

fun friendlyFailure(error: Throwable): String {
    val message = error.message.orEmpty()
    return when {
        message.contains("DRM", true) -> "This source is DRM-protected and cannot be downloaded."
        message.contains("login", true) ||
            message.contains("sign in", true) ||
            message.contains("age", true) -> "This source requires an account or age verification."
        message.contains("unsupported", true) -> "This public URL is not supported by the current engine."
        message.contains("space", true) ||
            message.contains("ENOSPC", true) -> "There is not enough storage space."
        message.contains("permission", true) ||
            message.contains("denied", true) -> "Download folder access was revoked. Choose the folder again."
        message.contains("timed out", true) ||
            message.contains("timeout", true) -> "The network timed out. Retry to continue the partial download."
        message.contains("initialize", true) ||
            message.contains("python", true) ||
            message.contains("engine", true) -> "The media engine could not start. Reset or update it in Settings."
        message.contains("network", true) ||
            error is java.io.IOException -> "The network transfer failed. Retry to continue the partial download."
        else -> message.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.removePrefix("ERROR: ")
            ?.take(180)
            ?: "The download failed. Try again."
    }
}

fun sanitizeFileName(value: String, fallback: String = "download"): String {
    val clean = value
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(Regex("[\\u0000-\\u001f<>:\"/\\\\|?*]"), "_")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '.')
        .take(180)
    return clean.ifBlank { fallback }
}

fun parseTransferLine(line: String): TransferProgress? {
    val percent = Regex("""(\d{1,3}(?:\.\d+)?)%""").find(line)
        ?.groupValues?.get(1)?.toFloatOrNull()?.toInt()?.coerceIn(0, 100)
        ?: return null
    val speed = Regex("""at\s+([\d.]+)([KMG]?i?B)/s""", RegexOption.IGNORE_CASE)
        .find(line)?.let { parseByteAmount(it.groupValues[1], it.groupValues[2]) }
    val eta = Regex("""ETA\s+(\d+):(\d+)(?::(\d+))?""", RegexOption.IGNORE_CASE)
        .find(line)?.groupValues?.drop(1)?.let { parts ->
            if (parts[2].isNotEmpty()) {
                parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toLong()
            } else {
                parts[0].toLong() * 60 + parts[1].toLong()
            }
        }
    return TransferProgress(percent, null, null, speed, eta)
}

private fun parseByteAmount(number: String, unit: String): Long {
    val multiplier = when (unit.uppercase()) {
        "KB" -> 1_000L
        "KIB" -> 1_024L
        "MB" -> 1_000_000L
        "MIB" -> 1_048_576L
        "GB" -> 1_000_000_000L
        "GIB" -> 1_073_741_824L
        else -> 1L
    }
    return (number.toDouble() * multiplier).toLong()
}
