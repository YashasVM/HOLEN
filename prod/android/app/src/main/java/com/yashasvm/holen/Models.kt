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
 *
 * [PinnedDns] has value equality so separately-created clients for the same
 * validated host/address set can reuse sockets from [publicConnectionPool].
 * An anonymous Dns instance here would make OkHttp consider each Address
 * different, forcing avoidable TCP/TLS handshakes for repeated metadata and
 * direct-download requests.
 */
internal fun pinnedPublicHttpsClient(
    endpoint: PublicHttpsEndpoint,
    timeoutMillis: Long,
): OkHttpClient {
    return OkHttpClient.Builder()
        .connectionPool(publicConnectionPool)
        .dns(PinnedDns(endpoint.host, endpoint.addresses))
        .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
}

private data class PinnedDns(
    private val expectedHost: String,
    private val addresses: List<InetAddress>,
) : Dns {
    private val normalizedHost = expectedHost.trimEnd('.').lowercase()

    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname.trimEnd('.').lowercase() != normalizedHost) {
            throw UnknownHostException("Unexpected hostname: $hostname")
        }
        return addresses
    }
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
    if (bytes.size != 4) return false
    val a = bytes[0].toInt() and 0xff
    val b = bytes[1].toInt() and 0xff
    val c = bytes[2].toInt() and 0xff
    return when {
        a == 0 -> false
        a == 10 -> false
        a == 100 && b in 64..127 -> false
        a == 127 -> false
        a == 169 && b == 254 -> false
        a == 172 && b in 16..31 -> false
        a == 192 && b == 0 && c == 0 -> false
        a == 192 && b == 0 && c == 2 -> false
        a == 192 && b == 88 && c == 99 -> false
        a == 192 && b == 168 -> false
        a == 198 && b in 18..19 -> false
        a == 198 && b == 51 && c == 100 -> false
        a == 203 && b == 0 && c == 113 -> false
        a >= 224 -> false
        else -> true
    }
}

private fun isPublicIpv6(bytes: ByteArray): Boolean {
    if (bytes.size != 16) return false
    if (bytes.all { it == 0.toByte() }) return false
    if (bytes.dropLast(1).all { it == 0.toByte() } && bytes.last() == 1.toByte()) return false
    val first = bytes[0].toInt() and 0xff
    val second = bytes[1].toInt() and 0xff
    if (first and 0xfe == 0xfc) return false // fc00::/7 unique local
    if (first == 0xfe && second and 0xc0 == 0x80) return false // fe80::/10 link local
    if (first == 0xff) return false // multicast
    if (first == 0x20 && second == 0x01) {
        val third = bytes[2].toInt() and 0xff
        val fourth = bytes[3].toInt() and 0xff
        if (third == 0x0d && fourth == 0xb8) return false // 2001:db8::/32 documentation
    }
    return true
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
        bytesDownloaded = candidate.bytesDownloaded ?: previous.bytesDownloaded,
        totalBytes = candidate.totalBytes ?: previous.totalBytes,
        speedBytesPerSecond = candidate.speedBytesPerSecond ?: previous.speedBytesPerSecond,
        etaSeconds = candidate.etaSeconds ?: previous.etaSeconds,
    )
}

private fun parseByteAmount(value: String, unit: String): Long? {
    val number = value.replace(",", "").toDoubleOrNull() ?: return null
    val multiplier = when (unit.uppercase()) {
        "B" -> 1.0
        "KB" -> 1_000.0
        "KIB" -> 1_024.0
        "MB" -> 1_000_000.0
        "MIB" -> 1_048_576.0
        "GB" -> 1_000_000_000.0
        "GIB" -> 1_073_741_824.0
        "TB" -> 1_000_000_000_000.0
        "TIB" -> 1_099_511_627_776.0
        else -> return null
    }
    return (number * multiplier).toLong()
}
