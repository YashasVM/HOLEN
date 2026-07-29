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
    return when {
        message.contains("DRM", true) -> "This source is DRM-protected and cannot be downloaded."
        message.contains("YouTube rejected the saved account session", true) ->
            "YouTube rejected the saved session. Update the media engine, then re-import fresh cookies from an age-verified account."
        message.contains("import fresh Netscape cookies", true) ->
            "This video needs YouTube sign-in. Import fresh cookies.txt from an age-verified account in Settings, then retry."
        message.contains("login", true) ||
            message.contains("sign in", true) ||
            message.contains("age", true) -> "This source requires an account or age verification."
        message.contains("unsupported", true) -> "This URL is not supported by the current engine."
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
    val total = Regex("""of\s+~?([\d.]+)([KMG]?i?B)""", RegexOption.IGNORE_CASE)
        .find(line)?.let { parseByteAmount(it.groupValues[1], it.groupValues[2]) }
    val downloaded = total?.let { it * percent / 100L }
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
    return TransferProgress(percent, downloaded, total, speed, eta)
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
