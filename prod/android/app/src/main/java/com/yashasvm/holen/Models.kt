package com.yashasvm.holen

import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient

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

class StorageException(message: String, cause: Throwable? = null) : IOException(message, cause)

fun validateHttpsUrl(raw: String): String {
    val value = raw.trim()
    require(value.length in 1..4096) { "Enter an HTTPS URL up to 4,096 characters." }
    val uri = runCatching { URI(value) }.getOrNull()
        ?: throw IllegalArgumentException("Enter a valid HTTPS URL.")
    require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
        "Only HTTPS links are supported."
    }
    require(uri.userInfo == null) { "Links containing credentials are not supported." }
    return uri.toASCIIString()
}

fun validatePublicHttpsUrl(raw: String): String {
    return resolvePublicHttpsEndpoint(raw).url
}

/**
 * An HTTPS URL and the public IP addresses it resolved to at validation time.
 *
 * Callers must use [pinnedPublicHttpsClient] rather than resolving [host]
 * again. Resolving once and then allowing a client to resolve again
 * leaves a window for DNS rebinding to an internal address.
 */
internal data class PublicHttpsEndpoint(
    val url: String,
    val host: String,
    val addresses: List<InetAddress>,
)

internal fun resolvePublicHttpsEndpoint(raw: String): PublicHttpsEndpoint {
    val url = validateHttpsUrl(raw)
    val host = requireNotNull(URI(url).host).trim('[', ']')
    val addresses = InetAddress.getAllByName(host).distinct()
    require(addresses.isNotEmpty() && addresses.all(::isPublicAddress)) {
        "Private and reserved network addresses are not supported."
    }
    return PublicHttpsEndpoint(url, host, addresses)
}

/**
 * Returns a client that can only resolve this endpoint's hostname to the
 * already-validated address bytes. TLS still receives the hostname, preserving
 * SNI and normal certificate verification.
 */
internal fun pinnedPublicHttpsClient(
    endpoint: PublicHttpsEndpoint,
    timeoutMillis: Long,
): OkHttpClient {
    return OkHttpClient.Builder()
        .connectionPool(publicConnectionPool)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                if (!hostname.trimEnd('.').equals(endpoint.host.trimEnd('.'), ignoreCase = true)) {
                    throw UnknownHostException("Unexpected hostname: $hostname")
                }
                return endpoint.addresses
            }
        })
        .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
}

private val publicConnectionPool = ConnectionPool(5, 5, TimeUnit.MINUTES)

internal fun isPublicAddress(address: InetAddress): Boolean {
    if (
        address.isAnyLocalAddress ||
        address.isLoopbackAddress ||
        address.isLinkLocalAddress ||
        address.isSiteLocalAddress ||
        address.isMulticastAddress
    ) {
        return false
    }
    return when (address) {
        is Inet4Address -> isPublicIpv4(address.address)
        is Inet6Address -> isPublicIpv6(address.address)
        else -> false
    }
}

private fun isPublicIpv4(bytes: ByteArray): Boolean {
    val first = bytes[0].toInt() and 0xff
    val second = bytes[1].toInt() and 0xff
    val third = bytes[2].toInt() and 0xff
    return when {
        first == 0 || first == 10 || first == 127 -> false
        first == 100 && second in 64..127 -> false
        first == 169 && second == 254 -> false
        first == 172 && second in 16..31 -> false
        first == 192 && second == 0 && third in setOf(0, 2) -> false
        first == 192 && second == 88 && third == 99 -> false
        first == 192 && second == 168 -> false
        first == 198 && second in setOf(18, 19) -> false
        first == 198 && second == 51 && third == 100 -> false
        first == 203 && second == 0 && third == 113 -> false
        first >= 224 -> false
        else -> true
    }
}

private fun isPublicIpv6(bytes: ByteArray): Boolean {
    val first = bytes[0].toInt() and 0xff
    val second = bytes[1].toInt() and 0xff
    val isMappedIpv4 = bytes.take(10).all { it == 0.toByte() } &&
        bytes[10] == 0xff.toByte() &&
        bytes[11] == 0xff.toByte()
    if (isMappedIpv4) return isPublicIpv4(bytes.copyOfRange(12, 16))
    val isDocumentation = first == 0x20 &&
        second == 0x01 &&
        bytes[2] == 0x0d.toByte() &&
        bytes[3] == 0xb8.toByte()
    return first !in 0xfc..0xff && !isDocumentation
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
    val normalized = message.lowercase()
    return when {
        normalized.contains("drm") -> "This source is DRM-protected and cannot be downloaded."
        normalized.contains("confirm you're not a bot") ||
            normalized.contains("confirm you’re not a bot") ||
            normalized.contains("verify you are human") ||
            normalized.contains("unusual traffic") ||
            normalized.contains("http error 429") ->
            "The source asked for a bot check. Wait a little, then retry; valid cookies may help for content you can access."
        isAgeRestrictedFailure(normalized) ->
            "This video needs age verification. Use fresh cookies from an account permitted to watch it, then retry."
        isLoginRequiredFailure(normalized) ->
            "This source needs a signed-in account. Add fresh cookies from an account permitted to access it, then retry."
        normalized.contains("unsupported") -> "This URL is not supported by the current engine."
        message.startsWith("Network response ", true) -> {
            val status = message.substringAfter("Network response ").takeWhile(Char::isDigit)
            if (status.isNotBlank()) {
                "The server returned HTTP $status. Check the link and try again."
            } else {
                "The server rejected the request. Check the link and try again."
            }
        }
        message.contains("space", true) ||
            message.contains("ENOSPC", true) -> "There is not enough storage space."
        error is StorageException -> message.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.take(180)
            ?: "The selected download folder could not be written."
        message.contains("permission", true) ||
            message.contains("denied", true) -> "Download folder access was revoked. Choose the folder again."
        message.contains("timed out", true) ||
            message.contains("timeout", true) -> "The network timed out. Retry to continue the partial download."
        message.contains("media engine startup failed", true) ||
            message.contains("engine failed to initialize", true) ||
            message.contains("could not initialize youtubedl", true) ||
            message.contains("dlopen failed", true) ||
            message.contains("libpython", true) ->
            "The media engine could not start. Reset or update it in Settings."
        message.contains("network", true) ||
            error is java.io.IOException -> "The network transfer failed. Retry to continue the partial download."
        else -> message.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.removePrefix("ERROR: ")
            ?.take(180)
            ?: "The download failed. Try again."
    }
}

private fun isAgeRestrictedFailure(message: String): Boolean = listOf(
    "age-restricted",
    "age restricted",
    "age verification",
    "verify your age",
    "confirm your age",
    "age-confirmation",
).any(message::contains)

private fun isLoginRequiredFailure(message: String): Boolean = listOf(
    "login required",
    "sign in required",
    "sign in to confirm",
    "please sign in",
    "authentication required",
    "members-only",
    "members only",
    "this video is private",
).any(message::contains)

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
    parseMachineTransferLine(line)?.let { return it }
    // yt-dlp's human-readable progress text is deliberately parsed here instead of
    // relying only on the Android wrapper.  The wrapper's built-in expression is
    // stricter than current yt-dlp output (for example, it misses "of ~ 12MiB").
    val percent = Regex("""(\d{1,3}(?:\.\d+)?)\s*%""").find(line)
        ?.groupValues?.get(1)?.toFloatOrNull()?.toInt()?.coerceIn(0, 100)
        ?: return null
    val total = Regex("""\bof\s+(?:~\s*)?([\d.,]+)\s*([KMGT]?i?B)""", RegexOption.IGNORE_CASE)
        .find(line)?.let { parseByteAmount(it.groupValues[1], it.groupValues[2]) }
    val downloaded = total?.let { it * percent / 100L }
    val speed = Regex("""\bat\s+([\d.,]+)\s*([KMGT]?i?B)/s""", RegexOption.IGNORE_CASE)
        .find(line)?.let { parseByteAmount(it.groupValues[1], it.groupValues[2]) }
    val eta = Regex("""\bETA\s+(?:(\d+):)?(\d{1,2}):(\d{2})""", RegexOption.IGNORE_CASE)
        .find(line)?.let { match ->
            val hours = match.groupValues[1].toLongOrNull() ?: 0L
            val minutes = match.groupValues[2].toLongOrNull() ?: 0L
            val seconds = match.groupValues[3].toLongOrNull() ?: 0L
            hours * 3600 + minutes * 60 + seconds
        }
    return TransferProgress(percent, downloaded, total, speed, eta)
}

/**
 * Parses HOLEN's explicit yt-dlp progress template.  Unlike yt-dlp's human
 * output this format is stable across locale, spacing, and total-size variants.
 * Values unavailable for a particular extractor are emitted as `NA`.
 */
fun parseMachineTransferLine(line: String): TransferProgress? {
    val fields = line.trim()
        .takeIf { it.startsWith(PROGRESS_MARKER) }
        ?.removePrefix(PROGRESS_MARKER)
        ?.trim()
        ?.split('|')
        ?: return null
    if (fields.size != 6) return null

    val percent = fields[0].trim().removeSuffix("%").toFloatOrNull()
        ?.toInt()?.coerceIn(0, 100)
        ?: return null
    val downloaded = fields[1].progressLong()
    val exactTotal = fields[2].progressLong()
    val estimatedTotal = fields[3].progressLong()
    return TransferProgress(
        percent = percent,
        bytesDownloaded = downloaded,
        totalBytes = exactTotal ?: estimatedTotal,
        speedBytesPerSecond = fields[4].progressLong(),
        etaSeconds = fields[5].progressLong(),
    )
}

private fun String.progressLong(): Long? = trim()
    .takeUnless { it.isBlank() || it.equals("NA", ignoreCase = true) }
    ?.toLongOrNull()

/** The exact token emitted by [YtDlpEngine] via `--progress-template`. */
const val PROGRESS_MARKER = "HOLEN_PROGRESS"

/**
 * Combines the library callback with yt-dlp's text progress.  A wrapper callback can
 * report -1 while a download is active, or retain an old value for unrelated output;
 * never turn either case into a visible 0% regression.
 */
fun transferProgressFromCallback(
    line: String,
    wrapperPercent: Float,
    wrapperEta: Long,
    previous: TransferProgress?,
): TransferProgress? {
    val parsed = parseTransferLine(line)
    val wrapperValue = wrapperPercent
        .takeIf { it.isFinite() && it in 0f..100f }
        ?.toInt()
    val rawPercent = parsed?.percent ?: wrapperValue ?: previous?.percent ?: return null
    val percent = maxOf(previous?.percent ?: 0, rawPercent).coerceIn(0, 100)
    val total = parsed?.totalBytes ?: previous?.totalBytes
    val downloaded = parsed?.bytesDownloaded
        ?: total?.let { it * percent / 100L }
        ?: previous?.bytesDownloaded
    val eta = parsed?.etaSeconds
        ?: wrapperEta.takeIf { it >= 0 }
        ?: previous?.etaSeconds
    return TransferProgress(
        percent = percent,
        bytesDownloaded = downloaded,
        totalBytes = total,
        speedBytesPerSecond = parsed?.speedBytesPerSecond ?: previous?.speedBytesPerSecond,
        etaSeconds = eta,
    )
}

/**
 * Preserves live state when independent progress sources race each other.  A
 * staging-file sampler can provide real transferred bytes while yt-dlp's
 * callback supplies the authoritative total, speed, and ETA a moment later.
 */
fun mergeTransferProgress(
    previous: TransferProgress?,
    candidate: TransferProgress,
): TransferProgress {
    if (previous == null) return candidate
    return TransferProgress(
        percent = maxOf(previous.percent, candidate.percent).coerceIn(0, 100),
        bytesDownloaded = listOfNotNull(previous.bytesDownloaded, candidate.bytesDownloaded).maxOrNull(),
        totalBytes = candidate.totalBytes ?: previous.totalBytes,
        speedBytesPerSecond = candidate.speedBytesPerSecond ?: previous.speedBytesPerSecond,
        etaSeconds = candidate.etaSeconds ?: previous.etaSeconds,
    )
}

private fun parseByteAmount(number: String, unit: String): Long {
    val multiplier = when (unit.uppercase()) {
        "KB" -> 1_000L
        "KIB" -> 1_024L
        "MB" -> 1_000_000L
        "MIB" -> 1_048_576L
        "GB" -> 1_000_000_000L
        "GIB" -> 1_073_741_824L
        "TB" -> 1_000_000_000_000L
        "TIB" -> 1_099_511_627_776L
        else -> 1L
    }
    return (number.replace(",", "").toDouble() * multiplier).toLong()
}
